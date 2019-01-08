package scappla

import java.io.InputStreamReader

import com.github.tototoshi.csv.CSVReader
import scappla.Functions.exp
import scappla.distributions.Normal
import scappla.guides.ReparamGuide
import scappla.optimization.Adam
import scappla.tensor.{Dim, Tensor, TensorExpr}

object TestChickweight extends App {

  import Real._

  val reader = CSVReader.open(new InputStreamReader(getClass.getResourceAsStream("/chickweight.csv")))
  val raw = reader.allWithHeaders()

  case class Record(weight: Float, time: Float, diet: Int)

  val data = raw.map { row =>
    Record(row("weight").toFloat, row("Time").toFloat, row("Diet").toInt)
  }.toArray

  case class Diet(size: Int) extends Dim[Diet]

  val n_diets = data.map {
    _.diet
  }.max
  val diets = for {diet <- 0 until n_diets} yield {
    val diet_data = data.filter(_.diet == diet)
    val dim = Diet(diet_data.length)
    val times = Array.ofDim[Float](dim.size)
    val weights = Array.ofDim[Float](dim.size)
    for {(record, index) <- diet_data.zipWithIndex} {
      times.update(index, record.time)
      weights.update(index, record.weight)
    }
    (
        dim,
        Tensor(dim, times),
        Tensor(dim, weights)
    )
  }


  val sgd = new Adam(alpha = 0.05, epsilon = 1e-4)
  val aPost = ReparamGuide(Normal(sgd.param(40.0, name=Some("a_mu")), exp(sgd.param(0.0))))
  val atPost = ReparamGuide(Normal(sgd.param(0.0, name=Some("a_s")), exp(sgd.param(0.0))))

  val data_with_guides = diets.zipWithIndex.map { case (d, i) =>
    (
        d,
        (
            ReparamGuide(Normal(sgd.param(10.0, name=Some(s"b_mu_${i}")), exp(sgd.param(1.0)))),
            ReparamGuide(Normal(sgd.param(0.0, name=Some(s"s_mu_${i}")), exp(sgd.param(0.0))))
        )
    )
  }

  import scappla.tensor.TensorExpr._
  val numTE = TensorExpr.numTensor[Diet, Array[Float]]

  val model = infer {
    val a = sample(Normal(40.0, 1.0), aPost)
    val a_t = exp(sample(Normal(0.0, 0.2), atPost))

    val b = for {
      ((dim, times, weights), (muGuide, sGuide)) <- data_with_guides
    } yield {
      val a_tensor = broadcast(a, dim)
      val a_t_tensor = broadcast(a_t, dim)

      val b_mu = sample(Normal(10.0, 3.0), muGuide)
      val b_s = exp(sample(Normal(0.0, 1.0), sGuide))

      val tc = times.const
      val mu_tensor = broadcast(b_mu, dim)
      val s_tensor = broadcast(b_s, dim)
      val mc = numTE.plus(a_tensor, numTE.times(mu_tensor, tc))
      val sc = numTE.plus(a_t_tensor, numTE.times(s_tensor, tc))
      observe(Normal(mc, sc), weights.const)
      (b_mu, b_s)
    }

    (a, a_t, b)
  }

  // warm up
  val N = 1000
  val startTime = System.currentTimeMillis()
  println("a,a_t,b1m,b1s,b2m,b2s,b3m,b3s,b4m,b4s")
  Range(0, N).foreach { i =>
    val (v_a, v_a_t, v_b) = sample(model)

    println(s"${v_a.v}, ${v_a_t.v}, ${
      v_b.sortBy(_._1).map {
        case (m, s) => s"${m.v}, ${s.v}"
      }.mkString(", ")
    }")
  }
  val endTime = System.currentTimeMillis()
  println(s"Time: ${endTime - startTime} (avg: ${(endTime - startTime) * 1000.0 / N} mus / sample)")

}
