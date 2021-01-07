package solution

import java.util.concurrent.atomic.AtomicReference

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, ExitCode, IO, IOApp, Resource}
import cats.syntax.all._

import scala.collection.immutable.Queue
import scala.util.Random

class FixedSizePool[A] private (stateRef: Ref[IO, FixedSizePool.State[A]])(implicit cs: ContextShift[IO]) {

  private def get: IO[A] = {
    Deferred[IO, A].flatMap { waiter =>
      stateRef.modify[Either[Deferred[IO, A], A]] { state =>
        state.available.dequeueOption match {
          case Some((value, newAvailable)) =>
            (state.copy(available = newAvailable), Right(value))
          case None =>
            (state.copy(waiters = state.waiters.enqueue(waiter)), Left(waiter))
        }
      }
    }.flatMap {
      case Right(entry) => IO(entry)
      case Left(waiter) => waiter.get
    }
  }

  private def put(a: A): IO[Unit] = {
    stateRef.modify { state =>
      state.waiters.dequeueOption match {
        case Some((waiter, newWaiters)) =>
          (state.copy(waiters = newWaiters), Some(waiter))
        case None =>
          (state.copy(available = state.available.enqueue(a)), None)
      }
    }.flatMap {
      case Some(waiter) => waiter.complete(a)
      case None => IO.unit
    }
  }

  def use[B](f: A => IO[B]): IO[B] = {
    get.flatMap { entry =>
      f(entry).guarantee {
        put(entry)
      }
    }
  }
}
object FixedSizePool {

  private case class State[A](
    available: Queue[A],
    waiters: Queue[Deferred[IO, A]],
  )

  def apply[A](size: Int, factory: Resource[IO, A])(implicit cs: ContextShift[IO]): Resource[IO, FixedSizePool[A]] = {
    List.fill(size)(factory).parSequence.flatMap { available =>
      val initialState = State[A](available = available.to(Queue), waiters = Queue.empty)
      Resource.liftF(Ref.of[IO, State[A]](initialState)).map { ref =>
        new FixedSizePool(ref)
      }
    }
  }
}

object Main extends IOApp {

  class Connection(val id: Int)

  def acquire: IO[Connection] = {
    IO {
      val id = Random.nextInt
      println(s"Connection $id created")
      new Connection(id)
    }
  }
  def release(connection: Connection): IO[Unit] = {
    IO(println(s"Closing connection ${connection.id}"))
  }

  val connectionResource: Resource[IO, Connection] = Resource.make(acquire)(release)

  def process(connection: Connection): IO[Unit] = {
    IO(println(s"Using connection ${connection.id}"))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    FixedSizePool[Connection](5, connectionResource).use { pool =>
      pool.use(process)
    }.as(ExitCode.Success)
  }
}
