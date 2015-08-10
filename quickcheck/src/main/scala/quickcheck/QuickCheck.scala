package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("gen1") = forAll { (h: H) =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, h)) == m
  }

  property("hint1") = forAll { (a: Int, b: Int) =>
    val h = insert(b, insert(a, empty))
    findMin(h) == math.min(a, b)
  }

  property("hint2") = forAll { (a: Int) =>
    val h = insert(a, empty)
    deleteMin(h) == empty
  }

  property("hint3") = forAll { (h: H) =>
    checkSort(findMin(h), h)
  }

  property("hint4") = forAll { (h1: H, h2: H) =>
    val min1 = findMin(h1)
    val min2 = findMin(h2)
    val min3 = findMin(meld(h1, h2))
    min1 == min3 || min2 == min3
  }

  property("meld") = forAll { (h1: H, h2: H) =>
    def isSameHeap(heap1: H, heap2: H): Boolean = {
      if(isEmpty(heap1) && isEmpty(heap2)) true
      else {
        val min1 = findMin(heap1)
        val min2 = findMin(heap2)
        min1 == min2 && isSameHeap(deleteMin(heap1), deleteMin(heap2))
      }
    }
    isSameHeap(meld(h1, h2), meld(deleteMin(h1), insert(findMin(h1), h2)))
  }

  val emptyHeap = const(empty)
  lazy val genHeap: Gen[H] = for {
    a <- arbitrary[A]
    h <- oneOf(emptyHeap, genHeap)
  } yield insert(a, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

  def checkSort(min : A, heap: H): Boolean = {
    if(isEmpty(heap)) true
    else {
      val next = findMin(heap)
      min <= next && checkSort(next, deleteMin(heap))
    }
  }
}
