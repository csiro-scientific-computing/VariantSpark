package au.csiro.variantspark.cli

import au.csiro.sparkle.common.args4j.ArgsApp
import au.csiro.sparkle.cmd.CmdApp
import org.kohsuke.args4j.Option
import au.csiro.pbdava.ssparkle.common.arg4j.AppRunner
import au.csiro.pbdava.ssparkle.spark.SparkApp
import collection.JavaConverters._
import au.csiro.variantspark.input.VCFSource
import au.csiro.variantspark.input.VCFFeatureSource
import au.csiro.variantspark.input.HashingLabelSource
import org.apache.spark.mllib.linalg.{Vector,Vectors}
import au.csiro.variantspark.input.CsvLabelSource
import au.csiro.variantspark.cmd.Echoable
import au.csiro.pbdava.ssparkle.common.utils.Logging
import org.apache.commons.lang3.builder.ToStringBuilder
import au.csiro.variantspark.cmd.EchoUtils._
import au.csiro.pbdava.ssparkle.common.utils.LoanUtils
import com.github.tototoshi.csv.CSVWriter
import au.csiro.pbdava.ssparkle.common.arg4j.TestArgs
import org.apache.hadoop.fs.FileSystem
import au.csiro.variantspark.algo.WideDecisionTree
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import au.csiro.pbdava.ssparkle.spark.SparkUtils
import au.csiro.pbdava.ssparkle.common.utils.ReusablePrintStream
import au.csiro.variantspark.algo.RandomForestCallback
import au.csiro.variantspark.utils.VectorRDDFunction._
import au.csiro.variantspark.input.CsvFeatureSource
import au.csiro.variantspark.algo.RandomForestParams
import au.csiro.variantspark.data.BoundedOrdinalVariable
import au.csiro.pbdava.ssparkle.common.utils.Timer
import au.csiro.variantspark.utils.defRng
import au.csiro.variantspark.input.ParquetFeatureSource
import au.csiro.variantspark.algo.ByteRandomForest
import au.csiro.variantspark.utils.IndexedRDDFunction._
import java.io.ObjectOutputStream
import java.io.FileOutputStream
import org.apache.hadoop.conf.Configuration
import au.csiro.variantspark.utils.HdfsPath
import au.csiro.pbdava.ssparkle.common.utils.CSVUtils
import au.csiro.variantspark.cli.args.ImportanceOutputArgs
import au.csiro.variantspark.cli.args.FeatureSourceArgs
import au.csiro.variantspark.data.ContinuousVariable
import au.csiro.variantspark.algo.RandomForest
import au.csiro.variantspark.algo.CanSplit
import au.csiro.variantspark.input.CanRepresent
import scala.reflect.ClassTag
import au.csiro.variantspark.input._
import au.csiro.variantspark.algo._
import au.csiro.variantspark.data.VariableType


class ImportanceCmd extends ArgsApp with SparkApp 
  with FeatureSourceArgs
  with ImportanceOutputArgs
  with Echoable with Logging with TestArgs {

  
  // input options  
  @Option(name="-ivt", required=false, usage="Input variable type, one of [`ord`, `cont` ] (def =  `ord` or vcf files `cont` otherwise)"
      , aliases=Array("--input-var-type"))
  val inputVariableTypeAsString:String = null
  def inputVariableTypeAsStringWithDefault:String = if (inputVariableTypeAsString != null) inputVariableTypeAsString else inputType match {
    case "vcf" => "ord"
    case _ => "cont"
  }
  
  // input options  
  @Option(name="-icl", required=false, usage="Number of levels for categorical variables with this number of levels (def = 3)" 
      , aliases=Array("--input-var-levels"))
  val varOrdinalLevels:Int = 3

  def inputVariableType:VariableType  = inputVariableTypeAsStringWithDefault match {
    case "cont" => ContinuousVariable
    case "ord" => BoundedOrdinalVariable(varOrdinalLevels)
    case _ => throw new IllegalArgumentException(s"Unknow variable type ${inputVariableTypeAsStringWithDefault}")
  }
  
  // input options  
  @Option(name="-ir", required=false, usage="Input representation, one of [`vector`, 'byteArray`]" 
      , aliases=Array("--input-represntation"))
  val inputRepresentationAsString:String = null
  def inputRepresentationAsStringWithDefault = if (inputRepresentationAsString != null) inputRepresentationAsString else inputType match {
      case "csv" => "vector"
      case _ => "byteArray"
    } 
  
  
  
  @Option(name="-ff", required=true, usage="Path to feature file", aliases=Array("--feature-file"))
  val featuresFile:String = null

  @Option(name="-fc", required=true, usage="Name of the feature column", aliases=Array("--feature-column"))
  val featureColumn:String = null
    
  // output options
  @Option(name="-of", required=false, usage="Path to output file (def = stdout)", aliases=Array("--output-file") )
  val outputFile:String = null
  
  @Option(name="-on", required=false, usage="The number of top important variables to include in output. Use `0` for all variables. (def=20)",
      aliases=Array("--output-n-variables"))
  val nVariables:Int = 20

  @Option(name="-od", required=false, usage="Include important variables data in output file (def=no)"
        , aliases=Array("--output-include-data") )
  val includeData = false
    
  @Option(name="-om", required=false, usage="Path to model file", aliases=Array("--model-file"))
  val modelFile:String = null

  // random forrest options
  
  @Option(name="-rn", required=false, usage="RandomForest: number of trees to build (def=20)", aliases=Array("--rf-n-trees") )
  val nTrees:Int = 20
 
  @Option(name="-rmt", required=false, usage="RandomForest: mTry(def=sqrt(<num-vars>))" , aliases=Array("--rf-mtry"))
  val rfMTry:Long = -1L
  
  @Option(name="-rmtf", required=false, usage="RandomForest: mTry fraction" , aliases=Array("--rf-mtry-fraction"))
  val rfMTryFraction:Double = Double.NaN
  
  @Option(name="-ro", required=false, usage="RandomForest: estimate oob (def=no)" , aliases=Array("--rf-oob"))
  val rfEstimateOob:Boolean = false

  
  @Option(name="-rre", required=false, usage="RandomForest: [DEPRICATED] randomize equal gini recursion is on by default now" , aliases=Array("--rf-randomize-equal"))
  val rfRandomizeEqual:Boolean = false

  
  @Option(name="-rsf", required=false, usage="RandomForest: sample with no replacement (def=1.0 for bootstrap  else 0.6666)" , aliases=Array("--rf-subsample-fraction"))
  val rfSubsampleFraction:Double = Double.NaN
  
  @Option(name="-rsn", required=false, usage="RandomForest: sample with no replacement (def=false -- bootstrap)" , aliases=Array("--rf-sample-no-replacement"))
  val rfSampleNoReplacement:Boolean = false

  @Option(name="-rbs", required=false, usage="RandomForest: batch size (def=10))" 
      , aliases=Array("--rf-batch-size"))
  val rfBatchSize:Int = 10

  @Option(name="-sr", required=false, usage="Random seed to use (def=<random>)", aliases=Array("--seed"))
  val randomSeed: Long = defRng.nextLong
   
  @Override
  def testArgs = Array("-if", "data/chr22_1000.vcf", "-ff", "data/chr22-labels.csv", "-fc", "22_16051249", "-ro", "-om", "target/ch22-model.ser", "-sr", "13")
    
  def runWithRepresentation[R](cr:CanRepresent[R], cs:CanSplit[R])(implicit ct:ClassTag[R]) = {
    implicit val fs = FileSystem.get(sc.hadoopConfiguration)  
    implicit val hadoopConf:Configuration = sc.hadoopConfiguration

    
    
    logDebug(s"Running with filesystem: ${fs}, home: ${fs.getHomeDirectory}")
    logInfo("Running with params: " + ToStringBuilder.reflectionToString(this))
    echo(s"Running with representation: ${ct.runtimeClass}")
 
    echo(s"Finding  ${nVariables}  most important features using random forest")

    val dataLoadingTimer = Timer()    
    echo(s"Loaded rows: ${dumpList(featureSource.sampleNames)}")
    val inputData = featureSource.featuresAs[R](cr).zipWithIndex().cache()
    val totalVariables = inputData.count()
    val variablePreview = inputData.map({case (f,i) => f.label}).take(defaultPreviewSize).toList
    echo(s"Loaded variables: ${dumpListHead(variablePreview, totalVariables)}, took: ${dataLoadingTimer.durationInSec}")
    echoDataPreview() 
    
    echo(s"Loading labels from: ${featuresFile}, column: ${featureColumn}")
    val labelSource = new CsvLabelSource(featuresFile, featureColumn)
    val labels = labelSource.getLabels(featureSource.sampleNames)
    echo(s"Loaded labels: ${dumpList(labels.toList)}")
    
    // discover variable type
    // for now assume it's ordered factor with provided number of levels
    val dataType = inputVariableType    
    echo(s"Data type is ${dataType} (represented on: ${ct.runtimeClass})")
    
    echo(s"Training random forest with trees: ${nTrees} (batch size:  ${rfBatchSize})")  
    echo(s"Random seed is: ${randomSeed}")
    val treeBuildingTimer = Timer()
    val rf:RandomForest[R] = new RandomForest[R](RandomForestParams(oob=rfEstimateOob, seed = randomSeed, bootstrap = !rfSampleNoReplacement, 
        subsample = rfSubsampleFraction, 
        nTryFraction = if (rfMTry > 0) rfMTry.toDouble/totalVariables else rfMTryFraction))(cs)
    val trainingData = inputData.map{ case (f, i) => (f.values, i)}
    
    implicit val rfCallback = new RandomForestCallback() {
      var totalTime = 0l
      var totalTrees = 0
      override   def onParamsResolved(actualParams:RandomForestParams) {
        echo(s"RF Params: ${actualParams}")
        echo(s"RF Params mTry: ${(actualParams.nTryFraction * totalVariables).toLong}")
      }
      override  def onTreeComplete(nTrees:Int, oobError:Double, elapsedTimeMs:Long) {
        totalTime += elapsedTimeMs
        totalTrees += nTrees
        echo(s"Finished trees: ${totalTrees}, current oobError: ${oobError}, totalTime: ${totalTime/1000.0} s, avg timePerTree: ${totalTime/(1000.0*totalTrees)} s")
        echo(s"Last build trees: ${nTrees}, time: ${elapsedTimeMs} ms, timePerTree: ${elapsedTimeMs/nTrees} ms")
        
      }
    }

    val result = rf.batchTrain(trainingData, dataType, labels, nTrees, rfBatchSize)
    
    echo(s"Random forest oob accuracy: ${result.oobError}, took: ${treeBuildingTimer.durationInSec} s") 
        
    if (modelFile != null) {
      LoanUtils.withCloseable(new ObjectOutputStream(HdfsPath(modelFile).create())) { objectOut =>
        objectOut.writeObject(result)
      }
    }
    
    // build index for names
    val topImportantVariables = limitVariables(result.normalizedVariableImportance(importanceNormalizer).toSeq, nVariables)
    val topImportantVariableIndexes = topImportantVariables.map(_._1).toSet
    
    val index = SparkUtils.withBroadcast(sc)(topImportantVariableIndexes) { br_indexes => 
      inputData.filter(t => br_indexes.value.contains(t._2)).map({case (f,i) => (i, f.label)}).collectAsMap()
    }
    
    val varImportance = topImportantVariables.map({ case (i, importance) => (index(i), importance)})
    
    if (isEcho && outputFile!=null) {
      echo("Variable importance preview")
      varImportance.take(math.min(math.max(nVariables, defaultPreviewSize), defaultPreviewSize)).foreach({case(label, importance) => echo(s"${label}: ${importance}")})
    }

    val importantVariableData = if (includeData) trainingData.collectAtIndexes(topImportantVariableIndexes) else null
    
    CSVUtils.withStream(if (outputFile != null ) HdfsPath(outputFile).create() else ReusablePrintStream.stdout) { writer =>
      val header = List("variable","importance") ::: (if (includeData) featureSource.sampleNames else Nil)
      writer.writeRow(header)
      writer.writeAll(topImportantVariables.map({case (i, importance) => 
        List(index(i), importance) ::: (if (includeData) cr.toListOfStrings(importantVariableData(i)) else Nil)}))
    }    
  }
  
 
  @Override
  def run():Unit = {
    implicit val fs = FileSystem.get(sc.hadoopConfiguration)  
    implicit val hadoopConf:Configuration = sc.hadoopConfiguration
    inputRepresentationAsStringWithDefault match {
      case "vector" => runWithRepresentation[Vector](CanRepresentVector, canSplitVector)
      case _ => runWithRepresentation[Array[Byte]](CanRepresentByteArray, canSplitArrayOfBytes)
    }    
  }
}

object ImportanceCmd  {
  def main(args:Array[String]) {
    AppRunner.mains[ImportanceCmd](args)
  }
}
