/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nicta.scoobi
package core

import impl.control.{ImplicitParameter2, ImplicitParameter1, ImplicitParameter}

/**
 * A list that is distributed across multiple machines.
 *
 * It supports a few Traversable-like methods:
 *
 * - parallelDo: a 'map' operation transforming elements of the list in parallel
 * - ++: to concatenate 2 DLists
 * - groupByKey: to group a list of (key, value) elements by key, so as to get (key, values)
 * - combine: a parallel 'reduce' operation
 * - materialise: transforms a distributed list into a non-distributed list
 */
trait DList[A] extends DataSinks with Persistent[Seq[A]] {
  type T = DList[A]
  type C <: CompNode

  private[scoobi]
  def getComp: C

  private[scoobi]
  def storeComp: scalaz.Store[C, DList[A]]

  implicit def wf: WireFormat[A] = getComp.wf.asInstanceOf[WireFormat[A]]

  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  // Primitive functionality.
  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /**
   * Apply a specified function to "chunks" of elements from the distributed list to produce
   * zero or more output elements. The resulting output elements from the many "chunks" form
   * a new distributed list
   */
  def parallelDo[B : WireFormat, E: WireFormat](env: DObject[E], dofn: EnvDoFn[A, B, E]): DList[B]

  /**Concatenate one or more distributed lists to this distributed list. */
  def ++(ins: DList[A]*): DList[A]

  /**Group the values of a distributed list with key-value elements by key. */
  def groupByKey[K, V](implicit ev: A <:< (K, V), wk: WireFormat[K], gpk: Grouping[K], wv: WireFormat[V]): DList[(K, Iterable[V])]

  /**Apply an associative function to reduce the collection of values to a single value in a
   * key-value-collection distributed list. */
  def combine[K, V](f: Reduction[V])(implicit ev: A <:< (K, Iterable[V]), wk: WireFormat[K], wv: WireFormat[V]): DList[(K, V)]

  @deprecated(message="use materialise instead", since="0.6.0")
  def materialize: DObject[Iterable[A]] = materialise

  /**
   * Turn a distributed list into a normal, non-distributed collection that can be accessed
   * by the client
   */
  def materialise: DObject[Iterable[A]]

  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  // Derived functionality (return DLists).
  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  def parallelDo[B : WireFormat](dofn: DoFn[A, B]): DList[B]
  def parallelDo[B : WireFormat](fn: (A, Emitter[B]) => Unit): DList[B] = basicParallelDo(fn)
  def parallelDo[B](fn: (A, Counters) => B)(implicit wf: WireFormat[B], p: ImplicitParameter): DList[B] = basicParallelDo(fn)(wf, p)
  def parallelDo[B](fn: (A, Heartbeat) => B)(implicit wf: WireFormat[B], p: ImplicitParameter1): DList[B] = basicParallelDo(fn)(wf, p)
  def parallelDo[B](fn: (A, ScoobiJobContext) => B)(implicit wf: WireFormat[B], p: ImplicitParameter2): DList[B] = basicParallelDo(fn)(wf, p)

  /**
   * Group the values of a distributed list with key-value elements by key. And explicitly
   * take the grouping that should be used. This is best used when you're doing things like
   * secondary sorts, or groupings with strange logic (like making sure None's / nulls are
   * sprayed across all reducers
   */
  def groupByKeyWith[K, V](grouping: Grouping[K])(implicit ev: A <:< (K, V), wfk: WireFormat[K], wfv: WireFormat[V]): DList[(K, Iterable[V])] =
    groupByKey(ev, wfk, grouping, wfv)

  /**
   * For each element of the distributed list produce zero or more elements by
   * applying a specified function. The resulting collection of elements form a
   * new distributed list
   */
  def mapFlatten[B : WireFormat](f: A => Iterable[B]): DList[B] =
    basicParallelDo((input: A, emitter: Emitter[B]) => f(input).foreach {
      emitter.emit(_)
    })

  @deprecated(message = "use mapFlatten instead because DList is not a subclass of Iterator and a well-behaved flatMap operation accepts an argument: A => DList[B]", since = "0.7.0")
  def flatMap[B : WireFormat](f: A => Iterable[B]): DList[B] = mapFlatten(f)

  /**
   * For each element of the distributed list produce a new element by applying a
   * specified function. The resulting collection of elements form a new
   * distributed list
   */
  def map[B : WireFormat](f: A => B): DList[B] =
    basicParallelDo((input: A, emitter: Emitter[B]) => emitter.emit(f(input)))

  /** Keep elements from the distributed list that pass a specified predicate function */
  def filter(p: A => Boolean): DList[A] =
    basicParallelDo((input: A, emitter: Emitter[A]) => if (p(input)) {
      emitter.emit(input)
    })

  /** the withFilter method */
  def withFilter(p: A => Boolean): DList[A] = filter(p)

  /** Keep elements from the distributed list that do not pass a specified predicate function */
  def filterNot(p: A => Boolean): DList[A] = filter(p andThen (!_))

  /**
   * Build a new DList by applying a partial function to all elements of this DList on
   * which the function is defined
   */
  def collect[B : WireFormat](pf: PartialFunction[A, B]): DList[B] =
    basicParallelDo((input: A, emitter: Emitter[B]) => if (pf.isDefinedAt(input)) {
      emitter.emit(pf(input))
    })

  /**Partitions this distributed list into a pair of distributed lists according to some
   * predicate. The first distributed list consists of elements that satisfy the predicate
   * and the second of all elements that don't. */
  def partition(p: A => Boolean): (DList[A], DList[A]) = (filter(p), filterNot(p))

  /**Converts a distributed list of iterable values into to a distributed list in which
   * all the values are concatenated. */
  def flatten[B](implicit ev: A <:< Iterable[B], mB: Manifest[B], wtB: WireFormat[B]): DList[B] =
    basicParallelDo((input: A, emitter: Emitter[B]) => input.foreach {
      emitter.emit(_)
    })

  /** Returns if the other DList has the same elements. A DList is unordered
   *  so order isn't considered. The Grouping required isn't very special and
   *  almost any will work (including grouping designed for secondary sorting)
   *  but for completeness, it is required to send two equal As to the
   *  same partition, and sortCompare provide total ordering
   */
  def isEqual(to: DList[A])(implicit cmp: Grouping[A]): DObject[Boolean] = {
    val left = map((_, false))
    val right = to.map((_, true))

    (left ++ right).groupByKey.parallelDo(
      new DoFn[(A, Iterable[Boolean]), Boolean] {
        var cntr: Long = _
        override def setup() {
          cntr = 0
        }
        override def process(in: (A, Iterable[Boolean]), e: Emitter[Boolean]) {
          if (cntr == 0) {
            for (b <- in._2) {
              cntr = cntr + (if (b) 1 else -1)
            }
          }
        }
        override def cleanup(e: Emitter[Boolean]) {
          e.emit(cntr == 0)
        }
      }).materialise.map(_.forall(identity))
  }

  /** Build a new distributed list from this list without any duplicate elements. */
  def distinct: DList[A] = {
    import scala.collection.mutable.{ Set => MSet }

    parallelDo(new BasicDoFn[A, (Int, A)] {

      var cache: MSet[A] = _

      override def setup() {
        cache = MSet.empty
      }

      override def process(input: A, emitter: Emitter[(Int, A)]) {
        if (!cache.contains(input)) {
          emitter.emit((input.hashCode, input))
          cache += input
        }
      }
    }).groupByKey.mapFlatten( _._2.toSet )
  }

  /** Add an index (Long) to the DList where the index is between 0 and .size-1 of the DList */
  def zipWithIndex: DList[(A, Long)] = {

    /* First we give every element in the DList a 'mapperId'
     * it doesn't affect the logic if there is collisions,
     * but it's preferable for there not to be. */
    val byMapper: DList[(Int, A)] = parallelDo(new DoFn[A, (Int, A)] {
      var mapperId: Int = _

      def setup()                                       { mapperId = scala.util.Random.nextInt()  }
      def process(input: A, emitter: Emitter[(Int, A)]) { emitter.emit(mapperId -> input) }
      def cleanup(emitter: Emitter[(Int, A)])           {}
    })

    /* Now we can count how many elements each mapper saw */
    val taskCount = byMapper.map(_._1 -> 1).groupByKey.combine(Reduction.Sum.int).materialise

    /* ..and then convert it to a series of offsets */
    val taskMap = taskCount.map { x =>
      x.foldLeft(Map[Int, Long]() -> 0l) { (state, next) =>
        val newMap = state._1 + (next._1 -> state._2)
        val newStartingPoint = (state._2 + next._2)
        newMap -> newStartingPoint
      }._1
    }

    /* Now simply send the data to the same (logical) mapper using our map to find the proper offset */
    (taskMap join byMapper.groupByKey).mapFlatten {
      case (env, (task, vals)) =>

        val index: Stream[Long] = {
          def loop(v: Long): Stream[Long] = v #:: loop(v + 1)
          loop(env(task))
        }

        vals.zip(index)
    }
  }

  /** Randomly shuffle a DList. */
  def shuffle: DList[A] =
    groupBy(_ => util.Random.nextInt())
      .mapFlatten(x => util.Random.shuffle(x._2)) // any key collisions must be shuffled

  /** Group the values of a distributed list according to some discriminator function. */
  def groupBy[K : WireFormat : Grouping](f: A => K): DList[(K, Iterable[A])] =
    by(f).groupByKey

  /** Group the value of a distributed list according to some discriminator function
    * and some grouping function. */
  def groupWith[K : WireFormat](f: A => K)(gpk: Grouping[K]): DList[(K, Iterable[A])] = {
    implicit def grouping = gpk
    by(f).groupByKey
  }

  /**Create a new distributed list that is keyed based on a specified function. */
  def by[K : WireFormat](kf: A => K): DList[(K, A)] = map {
    x => (kf(x), x)
  }

  /**Create a distributed list containing just the keys of a key-value distributed list. */
  def keys[K, V]
  (implicit ev: A <:< (K, V),
   mwk:  WireFormat[K],
   mwv:  WireFormat[V])
  : DList[K] = map(ev(_)._1)

  /**Create a distributed list containing just the values of a key-value distributed list. */
  def values[K, V]
  (implicit ev: A <:< (K, V),
   mwk:  WireFormat[K],
   mwv:  WireFormat[V])
  : DList[V] = map(ev(_)._2)


  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  // Derived functionality (reduction operations)
  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /**
   * Reduce the elements of this distributed list using the specified associative binary operator. The
   * order in which the elements are reduced is unspecified and may be non-deterministic
   */
  def reduce(op: Reduction[A]): DObject[A] = reduceOption(op).map(_.getOrElse(sys.error("the reduce operation is called on an empty list")))

  /**
   * Reduce the elements of this distributed list using the specified associative binary operator and a default value if
   * the list is empty. The order in which the elements are reduced is unspecified and may be non-deterministic
   */
  def reduceOption(op: Reduction[A]): DObject[Option[A]] = {
    /* First, perform in-mapper combining. */
    val imc: DList[A] = parallelDo(accumulateFunction[A](op))

    /* Group all elements together (so they go to the same reducer task) and then
     * combine them. */
    imc.groupBy(_ => 0).combine(op).map(_._2).headOption
  }

  private def accumulateFunction[S](op: Reduction[S]) = new DoFn[S, S] {
    private var acc: S = _
    private var none: Boolean = false

    def setup() { none = true }

    def process(input: S, emitter: Emitter[S]) {
      acc = if (none) input else op(acc, input)
      none = false
    }

    def cleanup(emitter: Emitter[S]) { if (!none) emitter.emit(acc) }
  }

  /**Multiply up the elements of this distribute list. */
  def product(implicit num: Numeric[A]): DObject[A] =
    reduceOption(Reduction(num.times)) map (_ getOrElse num.one)

  /**Sum up the elements of this distribute list. */
  def sum(implicit num: Numeric[A]): DObject[A] =
    reduceOption(Reduction(num.plus)) map (_ getOrElse num.zero)

  /**The length of the distributed list. */
  def length: DObject[Int] = map(_ => 1).sum

  /**The size of the distributed list. */
  def size: DObject[Int] = length

  /**Count the number of elements in the list which satisfy a predicate. */
  def count(p: A => Boolean): DObject[Int] = filter(p).length

  /**Find the largest element in the distributed list. */
  def max(implicit cmp: Ordering[A]): DObject[A] =
    reduce(Reduction.maximumS)

  /**Find the largest element in the distributed list. */
  def maxBy[B](f: A => B)(cmp: Ordering[B]): DObject[A] =
    reduce(Reduction.maximumS(cmp on f))

  /**Find the smallest element in the distributed list. */
  def min(implicit cmp: Ordering[A]): DObject[A] =
    reduce(Reduction.minimumS)

  /**Find the smallest element in the distributed list. */
  def minBy[B](f: A => B)(cmp: Ordering[B]): DObject[A] =
    reduce(Reduction.minimumS(cmp on f))

  /** @return the head of the DList as a DObject. This is an unsafe operation */
  def head: DObject[A] = materialise.map(_.head)

  /** @return the head of the DList as a DObject containing an Option */
  def headOption: DObject[Option[A]] = materialise.map(_.headOption)

  private def basicParallelDo[B : WireFormat](proc: (A, Emitter[B]) => Unit): DList[B] =
    parallelDo(BasicDoFn(proc))

  private def basicParallelDo[B](fn: (A, Counters) => B)(implicit wf: WireFormat[B], p: ImplicitParameter): DList[B] =
    basicParallelDo { (a: A, emitter: Emitter[B]) => emitter.write(fn(a, emitter))  }

  private def basicParallelDo[B](fn: (A, Heartbeat) => B)(implicit wf: WireFormat[B], p: ImplicitParameter1): DList[B] =
    basicParallelDo { (a: A, emitter: Emitter[B]) => emitter.write(fn(a, emitter))  }

  private def basicParallelDo[B](fn: (A, ScoobiJobContext) => B)(implicit wf: WireFormat[B], p: ImplicitParameter2): DList[B] =
    basicParallelDo { (a: A, emitter: Emitter[B]) => emitter.write(fn(a, emitter))  }
}

trait Persistent[T] extends DataSinks {
  type C <: CompNode

  private[scoobi]
  def getComp: C
}

