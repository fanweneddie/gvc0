package gvc.benchmarking

import gvc.benchmarking.DAO.{
  DBConnection,
  GlobalConfiguration,
  PathQueryCollection
}
import gvc.transformer.IR.Program
import gvc.Main
import gvc.benchmarking.Benchmark.BenchmarkException

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object BenchmarkPopulator {

  case class ProgramInformation(src: String,
                                ir: Program,
                                labels: LabelOutput,
                                fileName: String)

  case class StoredProgramRepresentation(info: ProgramInformation,
                                         componentMapping: Map[ASTLabel, Long])

  def futureToFutureTry[T](future: Future[T]): Future[Try[T]] =
    future map (Success(_)) recover { case x => Failure(x) }

  def allOf[T](futures: Seq[Future[T]]): Future[Seq[Try[T]]] =
    Future.sequence(futures map futureToFutureTry)

  def populate(populatorConfig: PopulatorConfig,
               libraryDirs: List[String]): Unit = {
    val connection = DAO.connect(populatorConfig.db)
    val globalConfig = DAO.resolveGlobalConfiguration(connection)
    populatePrograms(populatorConfig.sources,
                     libraryDirs,
                     globalConfig,
                     connection)
  }

  def sync(sources: List[Path],
           libraryDirs: List[String],
           connection: DBConnection): Map[Long, ProgramInformation] = {
    val synchronized = allOf(
      sources.map(src => {
        Future(syncProgram(src, libraryDirs, connection))
      })
    )
    val mapping = mutable.Map[Long, ProgramInformation]()
    Await
      .result(synchronized, Duration.Inf)
      .foreach {
        case Failure(f) => throw new BenchmarkException(f.getMessage)
        case Success(valueOption) =>
          valueOption match {
            case Some(value) => mapping += value
            case None        =>
          }
      }
    mapping.toMap
  }

  def populatePrograms(
      sources: List[Path],
      libraryDirs: List[String],
      globalConfig: GlobalConfiguration,
      connection: DBConnection): Map[Long, StoredProgramRepresentation] = {
    val synchronized = allOf(
      sources
        .map(src => {
          Future(populateProgram(src, libraryDirs, globalConfig, connection))
        }))

    val mapping = mutable.Map[Long, StoredProgramRepresentation]()
    Await
      .result(synchronized, Duration.Inf)
      .foreach {
        case Failure(f) => throw new BenchmarkException(f.getMessage)
        case Success(value) =>
          Output.info(s"Storing paths for ${value.rep.info.fileName}")
          value.pathQueries.foreach(q => q.exec(connection))
          mapping += (value.id -> value.rep)
      }
    mapping.toMap
  }

  private def syncProgram(
      src: Path,
      libraries: List[String],
      xa: DBConnection): Option[(Long, ProgramInformation)] = {

    Output.info(s"Syncing definitions for ${src.getFileName}")

    val sourceText = Files.readString(src)
    val sourceIR = Main.generateIR(sourceText, libraries)
    val labelOutput = new LabelVisitor().visit(sourceIR)
    val fileName = src.getFileName.toString
    val programInfo =
      ProgramInformation(sourceText, sourceIR, labelOutput, fileName)

    val insertedProgramID =
      DAO.resolveProgram(fileName,
                         md5sum(sourceText),
                         labelOutput.labels.size,
                         xa)

    insertedProgramID match {
      case Some(value) =>
        if (labelOutput.labels.indices.exists(i => {
              val l = labelOutput.labels(i)
              DAO.resolveComponent(value, l, xa).isEmpty
            })) None
        else {
          Some(value -> programInfo)
        }
      case None => None
    }
  }

  private def populatePaths(programID: Long,
                            programRep: StoredProgramRepresentation,
                            globalConfig: GlobalConfiguration,
                            xa: DBConnection): List[PathQueryCollection] = {
    val theoreticalMax =
      LabelTools.theoreticalMaxPaths(programRep.info.labels.labels.size)
    val sampler = new Sampler(programRep.info.labels)
    Output.info(s"Generating paths for ${programRep.info.fileName}")
    val bottomPermutationHash =
      new LabelPermutation(programRep.info.labels).idArray
    val bottomPerm =
      DAO.addOrResolvePermutation(programID, bottomPermutationHash, xa)

    val queryCollections = mutable.ListBuffer[PathQueryCollection]()

    val maximum =
      theoreticalMax.min(globalConfig.maxPaths).min(globalConfig.maxPaths)
    val difference = maximum - DAO.getNumberOfPaths(programID, xa)
    for (_ <- 0 until difference.intValue()) {

      val ordering = sampler.sample(SamplingHeuristic.Random)
      val pathHash =
        LabelTools
          .hashPath(programRep.info.labels.labels, ordering)
      if (!DAO.containsPath(programID, pathHash, xa)) {
        val pathQuery =
          new DAO.PathQueryCollection(pathHash, programID, bottomPerm)

        val currentPermutation =
          new LabelPermutation(programRep.info.labels)

        for (labelIndex <- ordering.indices) {
          currentPermutation.addLabel(ordering(labelIndex))
          val currentID = currentPermutation.idArray
          val storedPermutationID =
            DAO.addOrResolvePermutation(programID, currentID, xa)
          pathQuery.addStep(storedPermutationID,
                            programRep.componentMapping(ordering(labelIndex)))
        }
        queryCollections += pathQuery

      }
    }
    Output.success(
      s"Completed generating paths for ${programRep.info.fileName}")
    queryCollections.toList
  }

  case class PopulatedProgram(id: Long,
                              rep: StoredProgramRepresentation,
                              pathQueries: List[PathQueryCollection])

  private def populateProgram(src: Path,
                              libraries: List[String],
                              globalConfiguration: GlobalConfiguration,
                              xa: DBConnection): PopulatedProgram = {
    Output.info(s"Syncing definitions for ${src.getFileName}")
    val sourceText = Files.readString(src)
    val sourceIR = Main.generateIR(sourceText, libraries)
    val labelOutput = new LabelVisitor().visit(sourceIR)
    val fileName = src.getFileName.toString
    val programInfo =
      ProgramInformation(sourceText, sourceIR, labelOutput, fileName)
    val insertedProgramID = DAO.addOrResolveProgram(src,
                                                    md5sum(sourceText),
                                                    labelOutput.labels.size,
                                                    xa)
    val componentMapping = mutable.Map[ASTLabel, Long]()
    labelOutput.labels.indices.foreach(i => {
      val l = labelOutput.labels(i)
      val insertedComponentID =
        DAO.addOrResolveComponent(insertedProgramID, l, xa)
      componentMapping += (l -> insertedComponentID)
    })
    Output.success(s"Completed syncing ${src.getFileName}")

    val programRep =
      StoredProgramRepresentation(programInfo, componentMapping.toMap)
    PopulatedProgram(
      insertedProgramID,
      programRep,
      populatePaths(insertedProgramID, programRep, globalConfiguration, xa))
  }

  //https://alvinalexander.com/source-code/scala-method-create-md5-hash-of-string/
  private def md5sum(contents: String): String = {

    def prependWithZeros(pwd: String): String =
      "%1$32s".format(pwd).replace(' ', '0')

    import java.math.BigInteger
    import java.security.MessageDigest
    val md = MessageDigest.getInstance("MD5")
    val digest: Array[Byte] = md.digest(contents.getBytes)
    val bigInt = new BigInteger(1, digest)
    val hashedPassword = bigInt.toString(16).trim
    prependWithZeros(hashedPassword)
  }

}
