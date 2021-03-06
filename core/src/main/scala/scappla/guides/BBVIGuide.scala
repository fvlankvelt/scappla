package scappla.guides

import scappla._
import scappla.distributions.Distribution
import scappla.optimization.Average

// control variate
// Since a constant delta between score_p and score_q has an expectation value of zero,
// the average value can be subtracted in order to reduce the variance.
case class BBVIGuide[A](posterior: Distribution[A], control: Expr[Double, Unit] = Param(0.0)) extends Guide[A] {

  // samples the guide (= the approximation to the posterior)
  // use BBVI (with Rao Blackwellization)
  override def sample(interpreter: Interpreter, prior: Likelihood[A]): Variable[A] = {

    val value: A = posterior.sample(interpreter)
    val controlVar: Real = interpreter.eval(control)

    val node: BayesNode = new BayesNode {

      override val modelScore: Buffered[Double, Unit] = {
        prior.observe(interpreter, value)
      }

      override val guideScore: Buffered[Double, Unit] = {
        posterior.observe(interpreter, value)
      }

      private var logp: Real = modelScore
      private var logq: Real = guideScore

      override def addObservation(score: Score): Unit = {
        logp = logp + score
      }

      override def addVariable(modelScore: Score, guideScore: Score): Unit = {
        logp = logp + modelScore
        logq = logq + guideScore
      }

      // compute ELBO and backprop gradients
      override def complete(): Unit = {
        // backprop gradients to decoder
        modelScore.dv(1.0)

        // backprop gradients to encoder
        update(guideScore, logp, logq)

        // evaluate optimizer
        modelScore.complete()
        guideScore.complete()
      }

      /**
        * Backprop using BBVI - the guide (prior) score gradient is backpropagated
        * with as weight the Rao-Blackwellized delta between the model and guide
        * (full) score.  The average difference is used as the control variate, to reduce
        * variance of the gradient.
        */
      private def update(s: Score, logp: Real, logq: Real): Unit = {
        val delta = logp.v - logq.v

        val gradient = delta - controlVar.v
        controlVar.dv(gradient)

        s.dv(gradient)
      }

    }

    Variable(value, node)
  }
}
