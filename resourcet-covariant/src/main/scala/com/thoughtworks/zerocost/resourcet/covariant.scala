package com.thoughtworks.zerocost.resourcet

import cats.{Applicative, FlatMap, Foldable, Functor, Monad, MonadError}
import com.thoughtworks.zerocost.parallel.covariant.Parallel
import covariant._

import scala.language.higherKinds
import cats.syntax.all._
import com.thoughtworks.zerocost.LiftIO
import com.thoughtworks.zerocost.LiftIO.IO

private[thoughtworks] sealed abstract class CovariantResourceTInstances3 {

  /** @group Type classes */
  implicit def covariantResourceTApplicative[F[+ _]: Applicative]: Applicative[ResourceT[F, ?]] =
    new Serializable with CovariantResourceTApplicative[F] {
      override private[thoughtworks] def typeClass = implicitly
    }
}

private[thoughtworks] sealed abstract class CovariantResourceTInstances2 extends CovariantResourceTInstances3 {

  /** @group Type classes */
  implicit def covariantResourceTMonad[F[+ _]: Monad]: Monad[ResourceT[F, ?]] =
    new Serializable with CovariantResourceTMonad[F] {
      private[thoughtworks] override def typeClass = implicitly
    }
}

private[thoughtworks] sealed abstract class CovariantResourceTInstances1 extends CovariantResourceTInstances2 {}

private[thoughtworks] sealed abstract class CovariantResourceTInstances0 extends CovariantResourceTInstances1 {}

private[thoughtworks] trait CovariantResourceTPoint[F[+ _]] extends Applicative[ResourceT[F, ?]] {
  private[thoughtworks] implicit def typeClass: Applicative[F]

  override def pure[A](a: A): ResourceT[F, A] = covariant.ResourceT.pure(a)
}

private[thoughtworks] trait CovariantResourceTApplicative[F[+ _]]
    extends Applicative[ResourceT[F, ?]]
    with CovariantResourceTPoint[F] {

  override def ap[A, B](f: ResourceT[F, (A) => B])(fa: ResourceT[F, A]): ResourceT[F, B] = {
    ResourceT(
      Applicative[F].map2(opacityTypes.fromResourceT(fa), opacityTypes.fromResourceT(f)) { (releasableA, releasableF) =>
        val releaseA = releasableA.release
        Resource[F, B](
          value = releasableF.value(releasableA.value),
          release = Applicative[F].map2(releaseA, releasableF.release) { (_: Unit, _: Unit) =>
            ()
          }
        )
      }
    )
  }
}

private[thoughtworks] trait CovariantResourceTApplicativeIO[F[+ _]]
    extends LiftIO[ResourceT[F, ?]]
    with CovariantResourceTApplicative[F] {
  private[thoughtworks] implicit def L: LiftIO[F]

  override def liftIO[A](io: IO[A]) = covariant.ResourceT.liftIO(io)
}
private[thoughtworks] trait CovariantResourceTParallelApplicative[F[+ _]]
    extends Applicative[Parallel[ResourceT[F, `+?`], ?]] {
  private[thoughtworks] implicit def typeClass: Applicative[Parallel[F, ?]]

  override def map[A, B](pfa: Parallel[ResourceT[F, `+?`], A])(f: (A) => B): Parallel[ResourceT[F, `+?`], B] = {
    val Parallel(ResourceT(fa)) = pfa
    val Parallel(fb) = typeClass.map(Parallel(fa)) { releasableA: Resource[F, A] =>
      val releasableB: Resource[F, B] = new Serializable with Resource[F, B] {
        override val value: B = f(releasableA.value)
        override val release = releasableA.release
      }
      releasableB
    }
    Parallel(ResourceT(fb))
  }

  override def pure[A](a: A): Parallel[ResourceT[F, `+?`], A] = {
    Parallel({
      val Parallel(release) = covariant.callByNameUnitCache.pure[Parallel[F, ?]]
      val Parallel(fa) = typeClass.pure(Resource[F, A](value = a, release = release))
      ResourceT(fa)
    }: ResourceT[F, A])
  }

  override def ap[A, B](pf: Parallel[ResourceT[F, `+?`], A => B])(
      pfa: Parallel[ResourceT[F, `+?`], A]): Parallel[ResourceT[F, `+?`], B] = {
    val Parallel(ResourceT(fa)) = pfa
    val Parallel(ResourceT(f)) = pf
    val Parallel(fResourceB) = typeClass.map2(
      Parallel(fa),
      Parallel(f)
    ) { (resourceA, resourceF) =>
      val valueB = resourceF.value(resourceA.value)
      val releaseA = resourceA.release
      val releaseF = resourceF.release
      new Serializable with Resource[F, B] {
        override val value: B = valueB

        override val Parallel(release) = {

          typeClass.map2(Parallel(releaseA), Parallel(releaseF)) { (_: Unit, _: Unit) =>
            ()
          }

        }
      }
    }
    Parallel(ResourceT(fResourceB))
  }
}

private[thoughtworks] trait CovariantResourceTMonad[F[+ _]]
    extends CovariantResourceTApplicative[F]
    with Monad[ResourceT[F, ?]] {
  private[thoughtworks] implicit override def typeClass: Monad[F]

  override def tailRecM[A, B](begin: A)(f: A => ResourceT[F, Either[A, B]]): ResourceT[F, B] = {
    val fResourceB = typeClass.tailRecM(Resource.pure[F, A](begin)) {
      case Resource(a, release) =>
        val ResourceT(resourceEither) = f(a)
        resourceEither.map {
          case Resource(Left(nextA), releaseNext) =>
            Left(Resource(value = nextA, release = appendMonadicUnit(releaseNext, release)))
          case Resource(Right(nextB), releaseNext) =>
            Right(Resource(value = nextB, release = appendMonadicUnit(releaseNext, release)))
        }
    }
    ResourceT(fResourceB)
  }

  override def flatMap[A, B](fa: ResourceT[F, A])(f: (A) => ResourceT[F, B]): ResourceT[F, B] = {
    ResourceT(
      for {
        releasableA <- opacityTypes.fromResourceT(fa)
        releasableB <- opacityTypes.fromResourceT(f(releasableA.value))
      } yield {
        val b = releasableB.value
        val releaseB = releasableB.release
        val releaseA = releasableA.release
        new Serializable with Resource[F, B] {
          override def value: B = b

          override val release: F[Unit] = {
            covariant.appendMonadicUnit(releaseB, releaseA)
          }
        }
      }
    )
  }

}

/** The namespace that contains the covariant [[ResourceT]].
  *
  * Usage:
  * {{{
  * import com.thoughtworks.zerocost.resourcet.covariant._
  * }}}
  */
object covariant extends CovariantResourceTInstances0 {

  private[thoughtworks] final class CallByNameUnitCache(callByNameUnit: => Unit) {
    @inline
    def pure[F[_]](implicit applicative: Applicative[F]): F[Unit] = {
      applicative.pure(callByNameUnit)
    }
  }
// Disabled util com.thoughtworks.zerocost.continuation has been ported.
//  /** A cache of `=> Unit`.
//    *
//    * @note When using this cache to create two `UnitContinuation[UnitContinuation]`s,
//    *       {{{
//    *       import com.thoughtworks.continuation._
//    *       val continuation1 = covariant.callByNameUnitCache.pure[UnitContinuation]
//    *       val continuation2 = covariant.callByNameUnitCache.pure[UnitContinuation]
//    *       }}}
//    *       then the two continuations should equal to each other.
//    *
//    *       {{{
//    *       continuation1 should be(continuation2)
//    *       }}}
//    */
  private[thoughtworks] val callByNameUnitCache = new CallByNameUnitCache(())

  private[thoughtworks] def appendMonadicUnit[F[+ _]: Monad](f0: F[Unit], f1: F[Unit]): F[Unit] = {
    val noop = callByNameUnitCache.pure[F]
    if (f0 == noop) {
      f1
    } else if (f1 == noop) {
      f0
    } else {
      f0 >> f1
    }
  }

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]]
    * in order to prevent the Scala compiler seeing the actual type of [[ResourceT]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new Serializable with OpacityTypes {
    override type ResourceT[F[+ _], +A] = F[Resource[F, A]]

    override def toResourceT[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A] = run

    override def fromResourceT[F[+ _], A](resourceT: ResourceT[F, A]): F[Resource[F, A]] = resourceT
  }

  /** The data structure that provides automatic resource management.
    *
    * @example `ResourceT` can be used as a monad transformer for [[scala.Function0]]
    *
    *          {{{
    *          import scala.Function0
    *          import cats.instances.function._
    *          import com.thoughtworks.zerocost.resourcet.covariant._
    *          type RAII[A] = ResourceT[Function0, A]
    *          }}}
    *
    *          Given a resource that creates temporary files
    *
    *          {{{
    *          trait MyResource extends AutoCloseable {
    *            def inUse(): Unit
    *          }
    *          val myResourceStub0 = stub[MyResource]
    *          val myResourceStub1 = stub[MyResource]
    *          val myResourceFactoryMock = mockFunction[MyResource]
    *          myResourceFactoryMock.expects().returns(myResourceStub0)
    *          myResourceFactoryMock.expects().returns(myResourceStub1)
    *          val resource: RAII[MyResource] = ResourceT.autoCloseable[Function0, MyResource](myResourceFactoryMock)
    *          }}}
    *
    *          when using temporary file created by `resouce` in a  `for` / `yield` block,
    *          those temporary files should be available.
    *
    *          {{{
    *          import cats.syntax.all._
    *          val usingResouce = for {
    *            tmpFile0 <- resource
    *            tmpFile1 <- resource
    *          } yield {
    *            tmpFile0 should be(myResourceStub0)
    *            tmpFile1 should be(myResourceStub1)
    *            tmpFile1.inUse()
    *          }
    *          }}}
    *
    *          and those files should have been deleted after the `for` / `yield` block.
    *
    *          {{{
    *          usingResouce.run.apply()
    *          ((myResourceStub0.inUse _): () => Unit).verify().never()
    *          ((myResourceStub0.close _): () => Unit).verify().once()
    *
    *          ((myResourceStub1.inUse _): () => Unit).verify().repeated(30 to 40)
    *          ((myResourceStub1.close _): () => Unit).verify().once()
    *          }}}
    *
    * @note This `ResourceT` type is an opacity alias to `F[Resource[F, A]]`.
    *       All type classes and helper functions for this `ResourceT` type are defined in the companion object [[ResourceT$ ResourceT]]
    * @template
    */
  type ResourceT[F[+ _], +A] = opacityTypes.ResourceT[F, A]

  import opacityTypes._

  /** A container of a [[value]] and a function to [[release]] the `value`.
    * @note This [[Resource]] will become a case class. Use [[Resource.apply]] instead of `new Serializable with Resource[F, A] { ... }`.
    * @tparam A the type of [[value]]
    * @tparam F the monadic type of [[release]]
    */
  trait Resource[F[+ _], +A] {
    def value: A

    /** Releases [[value]] and all resource dependencies during creating [[value]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *       - [[value]] is a scoped native resource,
      *         e.g. this [[Resource]] is created from [[com.thoughtworks.raii.asynchronous.Do.scoped[Value<:AutoCloseable](value:=>Value)* scoped]],
      *       - or, [[value]] internally references some scoped native resources.
      */
    def release: F[Unit]
  }

  @deprecated(message = "Use [[Resource]] instead.", since = "3.0.0")
  type Releasable[F[+ _], +A] = Resource[F, A]

  object Resource {

    def unapply[F[+ _], A](resource: Resource[F, A]): Option[(A, F[Unit])] = Some((resource.value, resource.release))

    def apply[F[+ _], A](value: A, release: F[Unit]): Resource[F, A] = {
      val value0 = value
      val release0 = release
      new Serializable with Resource[F, A] {
        def value: A = value0

        def release: F[Unit] = release0

        override def toString: String = raw"""Resource($value, $release)"""
      }
    }

    @inline
    private[thoughtworks] def pure[F[+ _]: Applicative, A](value: A): Resource[F, A] = {
      Resource[F, A](value, callByNameUnitCache.pure[F])
    }
  }

  private[thoughtworks] trait OpacityTypes {
    type ResourceT[F[+ _], +A]

    private[thoughtworks] def toResourceT[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A]

    private[thoughtworks] def fromResourceT[F[+ _], A](resourceT: ResourceT[F, A]): F[Resource[F, A]]

  }

  /** An object that may hold resources until it is closed.
    *
    * Similar to [[java.lang.AutoCloseable]] except the close operation is monadic.
    *
    * @tparam F
    */
  trait MonadicCloseable[F[+ _]] extends Any {
    def monadicClose: F[Unit]
  }

  /** The companion object of [[ResourceT]] that contains converters and type classes.
    *
    * @note There are some implicit method that provides [[scalaz.Monad]]s as monad transformers of `F`.
    *       Those monads running will collect all resources,
    *       which will be open and release altogether when [[ResourceT.run]] is called.
    */
  object ResourceT {

    def pure[F[+ _]: Applicative, A](a: A): ResourceT[F, A] =
      ResourceT[F, A](Applicative[F].pure(Resource.pure(a)))

    def liftIO[F[+ _]: Applicative: LiftIO, A](io: LiftIO.IO[A]): ResourceT[F, A] = {
      ResourceT(LiftIO[F].liftIO(() => Resource.pure(io())))
    }

    def delay[F[+ _]: Applicative: LiftIO, A](a: => A): ResourceT[F, A] = liftIO(a _)

    def garbageCollected[F[+ _]: Applicative: LiftIO, A](fa: F[A]): ResourceT[F, A] = {
      ResourceT(fa.map(Resource.pure[F, A](_)))
    }

    def nested[F[+ _]: Monad: LiftIO, A](fa: ResourceT[F, A]): ResourceT[F, A] = {
      garbageCollected(fa.run)
    }

    def apply[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A] = opacityTypes.toResourceT(run)

    def unapply[F[+ _], A](resourceT: ResourceT[F, A]): Some[F[Resource[F, A]]] =
      Some(opacityTypes.fromResourceT(resourceT))

    def monadicCloseable[F[+ _]: Functor, A <: MonadicCloseable[F]](run: F[A]): ResourceT[F, A] = {
      val resource: F[Resource[F, A]] = run.map { a: A =>
        Resource(a, a.monadicClose)
      }
      ResourceT(resource)
    }

    def autoCloseable[F[+ _]: Functor: LiftIO, A <: AutoCloseable](run: F[A]): ResourceT[F, A] = {
      val resource: F[Resource[F, A]] = run.map { a: A =>
        Resource(a, LiftIO[F].delay {
          a.close()
        })
      }
      ResourceT(resource)
    }

  }

  /** @group Type classes */
  implicit def covariantResourceTParallelApplicative[F[+ _]](
      implicit F0: Applicative[Parallel[F, ?]]
  ): Applicative[Parallel[ResourceT[F, `+?`], ?]] = {
    new Serializable with CovariantResourceTParallelApplicative[F] {
      override private[thoughtworks] implicit def typeClass = F0
    }
  }

  private[thoughtworks] def catchError[F[+ _]: MonadError[?[_], S], S, A](fa: F[A]): F[S Either A] = {
    fa.map[Either[S, A]](Right(_)).handleErrorWith(e => Left(e).pure[F])
  }

  implicit final class CovariantResourceTOps[F[+ _], A](resourceT: ResourceT[F, A]) {

    /** Returns a `F` that performs the following process:
      *
      *  - Creating a [[Resource]] for `A`
      *  - Closing the [[Resource]]
      *  - Returning `A`
      */
    def run(implicit monad: FlatMap[F]): F[A] = {
      opacityTypes.fromResourceT(resourceT).flatMap { resource: Resource[F, A] =>
        val value = resource.value
        resource.release.map { _ =>
          value
        }
      }
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveMap` is to `map` in [[covariantResourceTMonad]],
      *       except `intransitiveMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: A => B)(implicit monad: Monad[F]): ResourceT[F, B] = {
      ResourceT(
        opacityTypes.fromResourceT(resourceT).flatMap { releasableA =>
          val b = f(releasableA.value)
          releasableA.release.map { _ =>
            new Serializable with Resource[F, B] {
              override val value: B = b

              override val release: F[Unit] = {
                callByNameUnitCache.pure[F]
              }
            }
          }
        }
      )
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates resource of `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[covariantResourceTMonad]],
      *       except `intransitiveFlatMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: A => ResourceT[F, B])(implicit flatMap: FlatMap[F]): ResourceT[F, B] = {
      ResourceT(
        for {
          releasableA <- opacityTypes.fromResourceT(resourceT)
          releasableB <- opacityTypes.fromResourceT(f(releasableA.value))
          _ <- releasableA.release
        } yield releasableB
      )
    }
  }
}
