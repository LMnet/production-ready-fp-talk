package solution

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, IO, Resource}
import cats.syntax.parallel._

import scala.collection.immutable.Queue

class FixedSizePoolProduction[A] private (
  stateRef: Ref[IO, FixedSizePoolProduction.State[A]],
)(implicit cs: ContextShift[IO]) {

  def use[B](f: A => IO[B]): IO[B] = {
    def useEntry(availableEntry: A): IO[B] = {
      f(availableEntry).guarantee {
        stateRef.modify { state =>
          state.waiting.dequeueOption match {
            case Some((deferred, newWaitingQueue)) =>
              (state.copy(waiting = newWaitingQueue), Some((deferred, availableEntry)))
            case None =>
              val newAvailableQueue = state.available.enqueue(availableEntry)
              (state.copy(available = newAvailableQueue), None)
          }
        }.flatMap {
          case Some((deferred, entry)) => deferred.complete(entry)
          case None => IO.unit
        }
      }
    }

    Deferred[IO, A].flatMap { deferred =>
      stateRef.modify[Either[Deferred[IO, A], A]] { state =>
        state.available.dequeueOption match {
          case Some((entry, newAvailableQueue)) =>
            (state.copy(available = newAvailableQueue), Right(entry))
          case None =>
            val newWaitingQueue = state.waiting.enqueue(deferred)
            (state.copy(waiting = newWaitingQueue), Left(deferred))
        }
      }
    }.flatMap {
      case Right(availableEntry) =>
        useEntry(availableEntry)

      case Left(deferred) =>
        deferred.get.flatMap { availableEntry =>
          useEntry(availableEntry)
        }
    }
  }
}
object FixedSizePoolProduction {

  def apply[A](
    size: Int,
    resource: Resource[IO, A],
  )(implicit cs: ContextShift[IO]): Resource[IO, FixedSizePoolProduction[A]] = {
    for {
      entries <- List.fill(size)(resource).parSequence
      initialState = State[A](entries.to(Queue), Queue.empty[Deferred[IO, A]])
      stateRef <- Resource.liftF(Ref[IO].of(initialState))
    } yield new FixedSizePoolProduction[A](stateRef)
  }

  private case class State[A](
    available: Queue[A],
    waiting: Queue[Deferred[IO, A]],
  )
}
