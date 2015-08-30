package suggestions



import language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}
import rx.lang.scala._
import org.scalatest._
import gui._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class WikipediaApiTest extends FunSuite {

  object mockApi extends WikipediaApi {
    def wikipediaSuggestion(term: String) = Future {
      if (term.head.isLetter) {
        for (suffix <- List(" (Computer Scientist)", " (Footballer)")) yield term + suffix
      } else {
        List(term)
      }
    }
    def wikipediaPage(term: String) = Future {
      "Title: " + term
    }
  }

  import mockApi._

  test("WikipediaApi should make the stream valid using sanitized") {
    val notvalid = Observable.just("erik", "erik meijer", "martin")
    val valid = notvalid.sanitized

    var count = 0
    var completed = false

    val sub = valid.subscribe(
      term => {
        println(term)
        assert(term.forall(_ != ' '))
        count += 1
      },
      t => assert(false, s"stream error $t"),
      () => completed = true
    )
    assert(completed && count == 3, "completed: " + completed + ", event count: " + count)
  }
  test("WikipediaApi should correctly use concatRecovered") {
    val requests = Observable.just(1, 2, 3)
    val remoteComputation = (n: Int) => Observable.just(0 to n : _*)
    val responses = requests concatRecovered remoteComputation
    val sum = responses.foldLeft(0) { (acc, tn) =>
      tn match {
        case Success(n) => acc + n
        case Failure(t) => throw t
      }
    }
    var total = -1
    val sub = sum.subscribe {
      s => total = s
    }
    assert(total == (1 + 1 + 2 + 1 + 2 + 3), s"Sum: $total")
  }

  test("WikipediaApi should correctly use concatRecovered with Error") {
    val exception = new Exception
    val requests = Observable.just(1, 2, 3, 4, 5)
    val remoteComputation = (num: Int) => if (num != 4) Observable.just(num) else Observable.error(exception)
    val responses = requests concatRecovered remoteComputation
    val observed = collection.mutable.Buffer[Try[Int]]()

    responses.subscribe(observed += _)

    assert(observed == collection.mutable.Buffer(Success(1), Success(2), Success(3), Failure(exception), Success(5)), observed)
  }

  test("WikipediaApi should correctly use concatRecovered with Another Stream") {
    val responses = Observable.just(1, 2, 3).concatRecovered(num => Observable.just(num, num, num))
    val observed = collection.mutable.Buffer[Try[Int]]()

    responses.subscribe(observed += _)
    assert(observed == collection.mutable.Buffer(
      Success(1), Success(1), Success(1), Success(2), Success(2), Success(2), Success(3), Success(3), Success(3)), observed)

  }
}
