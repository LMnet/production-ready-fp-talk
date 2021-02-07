package solution

import java.util.concurrent.atomic.AtomicReference

import cats.effect.{ExitCode, IO, IOApp, Resource, Deferred, Ref}
import cats.syntax.all._

import scala.collection.immutable.Queue
import scala.util.Random

class FixedSizePool[A] private (stateRef: Ref[IO, FixedSizePool.State[A]]) {

  private def get: IO[A] = {
    Deferred[IO, A].flatMap { deferred =>
      stateRef.modify[Either[Deferred[IO, A], A]] { state =>
        state.available.dequeueOption match {
          case Some((value, newAvailable)) =>
            (state.copy(available = newAvailable), Right(value))
          case None =>
            (state.copy(waiting = state.waiting.enqueue(deferred)), Left(deferred))
        }
      }
    }.flatMap {
      case Right(entry) => IO(entry)
      case Left(deferred) => deferred.get
    }
  }

  private def put(a: A): IO[Unit] = {
    stateRef.modify { state =>
      state.waiting.dequeueOption match {
        case Some((deferred, newWaiters)) =>
          (state.copy(waiting = newWaiters), Some(deferred))
        case None =>
          (state.copy(available = state.available.enqueue(a)), None)
      }
    }.flatMap {
      case Some(deferred) => deferred.complete(a).void
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
    waiting: Queue[Deferred[IO, A]],
  )

  def apply[A](size: Int, factory: Resource[IO, A]): Resource[IO, FixedSizePool[A]] = {
    List.fill(size)(factory).parSequence.flatMap { available =>
      val initialState = State[A](available = available.to(Queue), waiting = Queue.empty)
      Resource.eval(Ref.of[IO, State[A]](initialState)).map { ref =>
        new FixedSizePool(ref)
      }
    }
  }
}

object Main extends IOApp {

  class Connection(val id: Int)

  def acquire: IO[Connection] = {
    IO {
      val id = Random.between(0, 100)
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
      List.fill(10)(pool.use(process)).parSequence
    }.as(ExitCode.Success)
  }
}
