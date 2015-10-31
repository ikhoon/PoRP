/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import actorbintree.BinaryTreeNode
import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with ActorLogging{
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional

  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case operation : Operation => queueing(operation)
    case operationReply : OperationReply => reply(operationReply)
    case GC => {
      val newRoot = createRoot
      log.info(s"GC!! root : $root, newRoot : $newRoot")
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
    }
  }

  def queueing(operation: Operation) = {
    if(pendingQueue.isEmpty) execute(operation)
    pendingQueue = pendingQueue :+ operation
  }

  def execute(operation: Operation) = operation match {
      case Insert(req, id, el) => root ! Insert(self, id, el)
      case Contains(req, id, el) => root ! Contains(self, id, el)
      case Remove(req, id, el) => root ! Remove(self, id, el)
  }


  def reply(operationReply: OperationReply) = {
    val (operation, rest) = pendingQueue.dequeue
    operation.requester ! operationReply
    pendingQueue = rest
    if(rest.nonEmpty) execute(pendingQueue.front)
  }


  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case operation : Operation =>  {
      pendingQueue = pendingQueue :+ operation

    }
    case operationReply : OperationReply => {
      val (operation, rest) = pendingQueue.dequeue
      operation.requester ! operationReply
      pendingQueue = rest
    }
    case CopyFinished => {
      root ! PoisonPill
      root = newRoot
      if(pendingQueue.nonEmpty) execute(pendingQueue.front)
      context.become(normal)
    }
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor with ActorLogging{
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional


  /** Handles `Operation` messages and `CopyTo` requests. */
  /**
    * 1. GC
    *  - 자식 노드가 있을 경우 자식 노드에게 복사 명령을 내린다.
    *  - 자식 노드가 없을 경우 현재노드를 newRoot에 Insert 명령을 내리고 응답을 받으면(OperationFinished 상위 노드에 알려준다(CopyFinished)
    *  - 자식 노드가 CopyFinished보내는경우 모든 자식이 CopyFinished를 보낼때까지 기다린후 newRoot에 Insert명령을 위와 같이 한다.
    */
  val normal: Receive = {
    case i : Insert => insert(i)
    case c : Contains => contains(c)
    case r : Remove=> remove(r)
    case cp@CopyTo(treeNode) => {
      if(subtrees.isEmpty) {
        if(!removed) {
          treeNode ! Insert(self, 2222222, elem)
          context.become(waitAndFinish)
        }
        else {
          context.parent ! CopyFinished
        }
      }
      else {
        if(!removed) {
          treeNode ! Insert(self, 1111111, elem)
          context.become(waitAndCopying(cp))
        }
        else {
          subtrees.map {case (_, child) => child ! cp }
          context.become(copying(subtrees.values.toSet, false))
        }
      }
    }
  }

  def waitAndFinish : Receive = {
    case op : OperationFinished => {
      context.parent ! CopyFinished
    }
  }

  def waitAndCopying(cp: CopyTo) : Receive = {
    case op : OperationFinished => {
      subtrees.foreach {case (_, child) => child ! cp }
      context.become(copying(subtrees.values.toSet, false))
    }
  }

  def contains(op: Contains): Unit = {
    if(elem == op.elem) op.requester ! ContainsResult(op.id, !removed)
    else {
      val pos = position(op.elem)
      subtrees.get(pos) match {
        case Some(node) => node ! op
        case None => op.requester ! ContainsResult(op.id, false)
      }
    }
  }

  def insert(op: Insert): Unit = {
    if(elem == op.elem) {
      removed = false
      finish(op)
    }
    else {
      val pos = position(op.elem)
      subtrees.get(pos) match {
        case Some(node) => node ! op
        case None =>
          subtrees = subtrees + (pos -> context.actorOf(props(op.elem, false)))
          finish(op)
      }
    }
  }

  def remove(op: Remove): Unit = {
    if(elem == op.elem) {
      removed = true
      finish(op)
    }
    else {
      val pos = position(op.elem)
      subtrees.get(pos) match {
        case Some(node) => node ! op
        case None => finish(op)
      }
    }
  }

  private def finish(op: Operation) = {
    op.requester ! OperationFinished(op.id)
  }

  private def position(target: Int): Position = if(target <= elem) Left else Right

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
      case CopyFinished => {
        val remain = expected - sender()
        if(remain.isEmpty) {
          context.parent ! CopyFinished
        }
        else {
          context.become(copying(remain, remain.isEmpty))
        }
      }
  }

}
