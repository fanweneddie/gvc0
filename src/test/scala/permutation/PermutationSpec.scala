package gvc.specs.permutation

import gvc.Main
import gvc.benchmarking._
import gvc.specs._
import gvc.transformer.{IR, IRPrinter}
import gvc.transformer.IR.{Method, Predicate}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuite

import java.io.{File, FileWriter}
import java.nio.file.Files
import scala.collection.mutable

class PermutationSpec extends FixtureAnyFunSuite {
  test(
    "Labels used to generate a permutation match the contents of the result."
  ) { _ =>
    for (input <- TestUtils.groupResources("quant-study")) {
      val tempDir = Files.createTempDirectory("gvc0-permutation-spec")

      val sourceText = input(".c0").read()
      val includeDirectories = List("src/main/resources/")
      val ir = Main.generateIR(sourceText, includeDirectories)
      val labelOutput = new LabelVisitor().visit(ir)
      val sampler = new Sampler(labelOutput)

      for (_ <- 0 until 8) {
        val sampleToPermute =
          sampler.sample(SamplingHeuristic.Random)
        val selector = new SelectVisitor(ir)
        val auxLabeller = new LabelVisitor()

        def labelSet(labels: List[ASTLabel]): Set[String] = {
          labels
            .map(label => {
              val hash = label.toString
              hash.substring(0, hash.lastIndexOf('.'))
            })
            .toSet
        }

        val labelPermutation = new LabelPermutation(labelOutput)

        for (labelIndex <- sampleToPermute.indices) {
          labelPermutation.addLabel(sampleToPermute(labelIndex))

          val builtPermutation = selector.visit(labelPermutation)

          val builtLabels = auxLabeller.visit(builtPermutation)

          assert(
            labelSet(builtLabels.labels)
              .diff(labelSet(labelPermutation.labels.toList))
              .isEmpty
          )
        }

      }
      TestUtils.deleteDirectory(tempDir)
    }
  }
  test("Spec IDs and Expr IDs are contiguous and unique") { _ =>
    for (input <- TestUtils.groupResources("quant-study")) {
      val tempDir = Files.createTempDirectory("gvc0-imprecision-spec")
      val sourceText = input(".c0").read()
      val includeDirectories = List("src/main/resources/")
      val ir = Main.generateIR(sourceText, includeDirectories)
      val labelOutput = new LabelVisitor().visit(ir)

      val specIndices = mutable.Set[Int]()
      val exprIndices = mutable.Set[Int]()
      labelOutput.labels.foreach(l => {
        specIndices += l.specIndex
        assert(!exprIndices.contains(l.exprIndex) || l.exprIndex == -1)
        exprIndices += l.exprIndex
      })

      val maxSpecIndex = specIndices.max
      for (index <- 0 until maxSpecIndex)
        assert(specIndices.contains(index))

      val maxExprIndex = exprIndices.max
      for (index <- 0 until maxExprIndex)
        assert(exprIndices.contains(index))
    }
  }

  test("Each precondition, postcondition, and predicate body has a unique ID") {
    _ =>
      val preconditions = mutable.Map[Method, Int]()
      val postconditions = mutable.Map[Method, Int]()
      val predicateBodies = mutable.Map[Predicate, Int]()
      for (input <- TestUtils.groupResources("quant-study")) {
        val tempDir = Files.createTempDirectory("gvc0-imprecision-spec")
        val sourceText = input(".c0").read()
        val includeDirectories = List("src/main/resources/")
        val ir = Main.generateIR(sourceText, includeDirectories)
        val labelOutput = new LabelVisitor().visit(ir)

        for (label <- labelOutput.labels)
          label.parent match {
            case Left(value) =>
              label.specType match {
                case gvc.benchmarking.SpecType.Precondition =>
                  assert(
                    !preconditions
                      .contains(value) || preconditions(
                      value
                    ) == label.specIndex
                  )
                  preconditions += value -> label.specIndex
                case gvc.benchmarking.SpecType.Postcondition =>
                  assert(
                    !postconditions
                      .contains(value) || postconditions(
                      value
                    ) == label.specIndex
                  )
                  postconditions += value -> label.specIndex
                case _ =>
              }
            case Right(value) =>
              assert(label.specType == SpecType.Predicate)
              assert(
                !predicateBodies
                  .contains(value) || predicateBodies(value) == label.specIndex
              )
              predicateBodies += value -> label.specIndex
          }
        TestUtils.deleteDirectory(tempDir)
      }
  }

  test("The top specification is fully precise") { _ =>
    {
      for (input <- TestUtils.groupResources("quant-study")) {
        val tempDir = Files.createTempDirectory("gvc0-imprecision-spec")
        val sourceText = input(".c0").read()
        val includeDirectories = List("src/main/resources/")
        val ir = Main.generateIR(sourceText, includeDirectories)
        val labelOutput = new LabelVisitor().visit(ir)

        val baseline = IRPrinter.print(ir, includeSpecs = true)
        val sampler = new Sampler(labelOutput)
        for (_ <- 0 until 8) {
          val sampleToPermute =
            sampler.sample(SamplingHeuristic.Random)
          val selector = new SelectVisitor(ir)
          val labelPermutation = new LabelPermutation(labelOutput)
          for (labelIndex <- sampleToPermute.indices) {
            labelPermutation.addLabel(sampleToPermute(labelIndex))
          }
          val top = selector.visit(labelPermutation)
          val topPrinted = IRPrinter.print(top, includeSpecs = true)
          assert(topPrinted.diff(baseline).isEmpty)
        }
      }
    }
  }
  test(
    "Imprecision removal components are inserted in the correct positions."
  ) { _ =>
    for (input <- TestUtils.groupResources("quant-study")) {
      val tempDir = Files.createTempDirectory("gvc0-imprecision-spec")
      val sourceText = input(".c0").read()
      val includeDirectories = List("src/main/resources/")
      val ir = Main.generateIR(sourceText, includeDirectories)
      val labelOutput = new LabelVisitor().visit(ir)

      val sampler = new Sampler(labelOutput)
      for (_ <- 0 until 8) {
        val ordering = sampler.sample(SamplingHeuristic.Random)
        val lastComponents = mutable.Map[Int, (ASTLabel, Int)]()
        val methodCompletionCounts = mutable.Map[IR.Method, Int]()
        val methodCompletedAt = mutable.Map[IR.Method, Int]()
        val imprecisionCount = mutable.Map[Int, Int]()
        val uniqueMethods = mutable.Set[IR.Method]()
        for (labelIndex <- ordering.indices) {
          val label = ordering(labelIndex)
          label.specType match {
            case gvc.benchmarking.SpecType.Fold |
                gvc.benchmarking.SpecType.Unfold =>
              assert(label.parent.isLeft)
              val methodContext = label.parent.left.get
              uniqueMethods += methodContext
              methodCompletionCounts += (methodContext -> (methodCompletionCounts
                .getOrElse(methodContext, 0) + 1))
              if (methodCompletionCounts.getOrElse(
                    methodContext,
                    0
                  ) == labelOutput
                    .foldUnfoldCount(methodContext))
                methodCompletedAt(methodContext) = labelIndex
            case gvc.benchmarking.SpecType.Assert =>
            case _ =>
              lastComponents += label.specIndex -> (label, labelIndex)
          }
          label.exprType match {
            case gvc.benchmarking.ExprType.Imprecision =>
              imprecisionCount += (label.specIndex -> (imprecisionCount
                .getOrElse(label.specIndex, 0) + 1))
            case _ =>
          }
        }
        assert(imprecisionCount.values.toSet.size == 1)
        assert(uniqueMethods.size == methodCompletedAt.size)
        val pairList = lastComponents.toList
        for (pair <- pairList) {
          val label = pair._2._1
          label.specType match {
            case gvc.benchmarking.SpecType.Fold |
                gvc.benchmarking.SpecType.Unfold =>
            case _ =>
              assert(label.exprType.equals(ExprType.Imprecision))
              label.parent match {
                case Left(value) =>
                  val methodCompletionIndex =
                    methodCompletedAt.getOrElse(value, 0)
                  val imprecisionRemovalPoint = pair._2._2
                  assert(methodCompletionIndex < imprecisionRemovalPoint)
                case Right(_) =>
              }
          }
        }
      }
      TestUtils.deleteDirectory(tempDir)
    }
  }

  case class Args()

  type FixtureParam = Args

  override protected def withFixture(test: OneArgTest): Outcome = {
    try {
      test(Args())
    } finally {}
  }
}
