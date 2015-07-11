package org.utad.flopez

import org.apache.spark.mllib.clustering._
import org.apache.spark.mllib.linalg._
import org.apache.spark.rdd._
import org.apache.spark.{ SparkConf, SparkContext }
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.feature._
import java.util.Calendar
import java.io.{ FileWriter, BufferedWriter, File }
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import java.lang.Math.sqrt
import java.lang.Math.abs
import org.apache.spark.mllib.clustering.StreamingKMeans

// kmeans sample. 
// Variables that are text are removed and left as doubles. The positions are known.

/**
 * Full test about kmeans samples
 */

object testKmeansFull {
  def main(args: Array[String]): Unit = {
    val sc = new SparkContext(new SparkConf().setAppName("testKmeansFull").setMaster("local[*]"))
    val numVarPCA = 20
    val setRunsKM = 10

    val rawData = sc.textFile("ds/SUMMIT_500.csv")
    //clusteringTake0(rawData)
    //clusteringTake1(rawData)
    //clusteringTake2(rawData)
    //clusteringTake3(rawData)
    clusteringTake4(rawData)
    anomalies(rawData)
  }
  def clusteringTake0(rawData: RDD[String]): Unit = {

    rawData.map(_.split(',').last).countByValue().toSeq.sortBy(_._2).reverse.foreach(println)

    val parseFunction = buildLabelFunction(rawData)
    val labelsAndData = rawData.map(parseFunction)

    val data = labelsAndData.values.cache()

    val kmeans = new KMeans()
    val model = kmeans.run(data)

    model.clusterCenters.foreach(println)

    val clusterLabelCount = labelsAndData.map {
      case (label, datum) =>
        val cluster = model.predict(datum)
        (cluster, label)
    }.countByValue()

    clusterLabelCount.toSeq.sorted.foreach {
      case ((cluster, label), count) =>
        println(f"$cluster%1s$label%18s$count%8s")
    }

    data.unpersist()
  }

  def distance(a: Vector, b: Vector) =
    math.sqrt(a.toArray.zip(b.toArray).map(p => p._1 - p._2).map(d => d * d).sum)

  def distToCentroid(datum: Vector, model: KMeansModel) = {
    val cluster = model.predict(datum)
    val centroid = model.clusterCenters(cluster)
    distance(centroid, datum)
  }

  def clusteringScore(data: RDD[Vector], k: Int): Double = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    val model = kmeans.run(data)
    data.map(datum => distToCentroid(datum, model)).mean()
  }

  def clusteringScore2(data: RDD[Vector], k: Int): Double = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(data)
    data.map(datum => distToCentroid(datum, model)).mean()
  }

  def clusteringTake1(rawData: RDD[String]): Unit = {

    val parseFunction = buildLabelFunction(rawData)
    val data = rawData.map(parseFunction).values

    (5 to 30 by 5).map(k => (k, clusteringScore(data, k))).
      foreach(println)

    (30 to 100 by 10).par.map(k => (k, clusteringScore2(data, k))).
      toList.foreach(println)

    data.unpersist()

  }

  def clusteringTake2(rawData: RDD[String]): Unit = {
    val parseFunction = buildLabelFunction(rawData)
    val data = rawData.map(parseFunction).values

    val normalizedData = data.map(buildNormalizationFunction(data)).cache()

    (60 to 120 by 10).par.map(k =>
      (k, clusteringScore2(normalizedData, k))).toList.foreach(println)

    normalizedData.unpersist()
  }

  def clusteringTake3(rawData: RDD[String]): Unit = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val data = rawData.map(parseFunction).values
    val normalizedData = data.map(buildNormalizationFunction(data)).cache()

    (80 to 160 by 10).map(k =>
      (k, clusteringScore2(normalizedData, k))).toList.foreach(println)

    normalizedData.unpersist()
  }

  def clusteringTake4(rawData: RDD[String]): Unit = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val labelsAndData = rawData.map(parseFunction)
    val normalizedLabelsAndData =
      labelsAndData.mapValues(buildNormalizationFunction(labelsAndData.values)).cache()
    val kValue = stats4K(normalizedLabelsAndData.values)
    val outputDir = "ds/"
    (1 to kValue by 1).map(k =>
      (k, clusteringScore3(normalizedLabelsAndData, k))).toList.foreach { x =>
      printToFile(outputDir, "clusteringTake4-full", x.toString())
    }

    normalizedLabelsAndData.unpersist()
  }

  def entropy(counts: Iterable[Int]) = {
    val values = counts.filter(_ > 0)
    val n: Double = values.sum
    values.map { v =>
      val p = v / n
      -p * math.log(p)
    }.sum
  }

  def clusteringScore3(normalizedLabelsAndData: RDD[(String, Vector)], k: Int) = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)

    val model = kmeans.run(normalizedLabelsAndData.values)

    // Predict cluster for each datum
    val labelsAndClusters = normalizedLabelsAndData.mapValues(model.predict)

    // Swap keys / values
    val clustersAndLabels = labelsAndClusters.map(_.swap)

    // Extract collections of labels, per cluster
    val labelsInCluster = clustersAndLabels.groupByKey().values

    // Count labels in collections
    val labelCounts = labelsInCluster.map(_.groupBy(l => l).map(_._2.size))

    // Average entropy weighted by cluster size
    val n = normalizedLabelsAndData.count()

    labelCounts.map(m => m.sum * entropy(m)).sum / n
  }

  def buildNormalizationFunction(data: RDD[Vector]): (Vector => Vector) = {
    val dataAsArray = data.map(_.toArray)
    val numCols = dataAsArray.first().length
    val n = dataAsArray.count()
    val sums = dataAsArray.reduce(
      (a, b) => a.zip(b).map(t => t._1 + t._2))
    val sumSquares = dataAsArray.fold(
      new Array[Double](numCols))(
        (a, b) => a.zip(b).map(t => t._1 + t._2 * t._2))
    val stdevs = sumSquares.zip(sums).map {
      case (sumSq, sum) => math.sqrt(n * sumSq - sum * sum) / n
    }
    val means = sums.map(_ / n)

    (datum: Vector) => {
      val normalizedArray = (datum.toArray, means, stdevs).zipped.map(
        (value, mean, stdev) =>
          if (stdev <= 0) (value - mean) else {
            val d = (value - mean) / stdev
            if (Double.NaN.equals(d)) 0.0 else d
          })
      Vectors.dense(normalizedArray)
    }
  }

  // WE must know the input type
  def buildCategoricalAndLabelFunction(rawData: RDD[String]): (String => (String, Vector)) = {

    val splitData = rawData.map(_.split(",", 28))
    // Variables to categorize.
    //val TRADE_DATE_INDEXs = splitData.map(_(0).toDouble).first
    val PRODUCT_TYPE_INDEXs = splitData.map(_(1)).distinct().collect().zipWithIndex.toMap
    val TRADE_ID_INDEXs = splitData.map(_(2)).distinct().collect().zipWithIndex.toMap
    val BOOK_ID_INDEXs = splitData.map(_(3)).distinct().collect().zipWithIndex.toMap
    val COUNTERPARTY_INDEXs = splitData.map(_(4)).distinct().collect().zipWithIndex.toMap
    //val START_DATE_INDEXs = splitData.map(_(5).toDouble)
    //val QUANTITY_INDEXs = splitData.map(_(6).toDouble)
    val CURRENCY_INDEXs = splitData.map(_(7)).distinct().collect().zipWithIndex.toMap
    val INSTRUMENT_DESCRIPTION_INDEXs = splitData.map(_(8)).distinct().collect().zipWithIndex.toMap
    val BUY_SELL_INDEXs = splitData.map(_(9)).distinct().collect().zipWithIndex.toMap
    val TRADE_MATURITY_DATE_INDEXs = splitData.map(_(10)).distinct().collect().zipWithIndex.toMap
    val TRADE_MOD_DATE_INDEXs = splitData.map(_(11)) // Remove this variable!!! date + time => not signicant value
    //val TRADE_VERSION_INDEXs = splitData.map(_(12).toDouble)
    val TRADE_DESCRIPTION_INDEXs = splitData.map(_(13)).distinct().collect().zipWithIndex.toMap
    //val EFFECTIVE_DATE_INDEXs = splitData.map(_(14).toDouble)
    val LEGAL_ENTITY_INDEXs = splitData.map(_(15)).distinct().collect().zipWithIndex.toMap
    val TRADING_DESK_INDEXs = splitData.map(_(16)).distinct().collect().zipWithIndex.toMap
    val SYMBOL_INDEXs = splitData.map(_(17)).distinct().collect().zipWithIndex.toMap
    //val ORIGINAL_NOTIONAL_INDEXs = splitData.map(_(18).toDouble)
    //val NOTIONAL_INDEXs = splitData.map(_(19).toDouble)
    val TYPE_COLs = splitData.map(_(20)).distinct().collect().zipWithIndex.toMap
    //val SEC_AMOUNT_INDEXs = splitData.map(_(21).toDouble)
    val STATUS_INDEXs = splitData.map(_(22)).distinct().collect().zipWithIndex.toMap
    val AUDIT_ACTION_INDEXs = splitData.map(_(23)).distinct().collect().zipWithIndex.toMap
    val TYPE_CFTR_COLs = splitData.map(_(24)).distinct().collect().zipWithIndex.toMap
    val CURRENCY_CFTR_INDEXs = splitData.map(_(25)).distinct().collect().zipWithIndex.toMap
    //val NOTIONAL_CFTR_INDEXs = splitData.map(_(26).toDouble)
    //val REMAINING_NOTIONAL_CFTR_INDEXs = splitData.map(_(27).toDouble)

    (line: String) => {
      val buffer = line.split(",", 28).toBuffer

      //val TRADE_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val PRODUCT_TYPE_INDEX = buffer.remove(1)
      val TRADE_ID_INDEX = buffer.remove(1)
      val label = TRADE_ID_INDEX
      val BOOK_ID_INDEX = buffer.remove(1)
      val COUNTERPARTY_INDEX = buffer.remove(1)
      //val START_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val QUANTITY_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val CURRENCY_INDEX = buffer.remove(3)
      val INSTRUMENT_DESCRIPTION_INDEX = buffer.remove(3)
      val BUY_SELL_INDEX = buffer.remove(3)
      val TRADE_MATURITY_DATE_INDEX = buffer.remove(3)
      val TRADE_MOD_DATE_INDEX = buffer.remove(3)
      //val TRADE_VERSION_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val TRADE_DESCRIPTION_INDEX = buffer.remove(4)
      //val EFFECTIVE_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val LEGAL_ENTITY_INDEX = buffer.remove(5)
      val TRADING_DESK_INDEX = buffer.remove(5)
      val SYMBOL_INDEX = buffer.remove(5)
      //val ORIGINAL_NOTIONAL_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val NOTIONAL_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val TYPE_COL = buffer.remove(7)
      //val SEC_AMOUNT_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val STATUS_INDEX = buffer.remove(8)
      val AUDIT_ACTION_INDEX = buffer.remove(8)
      val TYPE_CFTR_COL = buffer.remove(8)
      val CURRENCY_CFTR_INDEX = buffer.remove(8)
      //val NOTIONAL_CFTR_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val REMAINING_NOTIONAL_CFTR_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble

      // None must be left
      // Values = "spaces" get into 0.0 value
      val vector = buffer.map(x => if ("".equals(x)) 0 else x.toDouble)

      val newPRODUCT_TYPE_INDEX = new Array[Double](PRODUCT_TYPE_INDEXs.size)
      newPRODUCT_TYPE_INDEX(PRODUCT_TYPE_INDEXs(PRODUCT_TYPE_INDEX)) = 1.0

      val newTRADE_ID_INDEX = new Array[Double](TRADE_ID_INDEXs.size)
      newTRADE_ID_INDEX(TRADE_ID_INDEXs(TRADE_ID_INDEX)) = 1.0

      val newBOOK_ID_INDEX = new Array[Double](BOOK_ID_INDEXs.size)
      newBOOK_ID_INDEX(BOOK_ID_INDEXs(BOOK_ID_INDEX)) = 1.0

      val newBUY_SELL_INDEX = new Array[Double](BUY_SELL_INDEXs.size)
      newBUY_SELL_INDEX(BUY_SELL_INDEXs(BUY_SELL_INDEX)) = 1.0

      val newCOUNTERPARTY_INDEX = new Array[Double](COUNTERPARTY_INDEXs.size)
      newCOUNTERPARTY_INDEX(COUNTERPARTY_INDEXs(COUNTERPARTY_INDEX)) = 1.0

      val newCURRENCY_INDEX = new Array[Double](CURRENCY_INDEXs.size)
      newCURRENCY_INDEX(CURRENCY_INDEXs(CURRENCY_INDEX)) = 1.0

      val newINSTRUMENT_DESCRIPTION_INDEX = new Array[Double](INSTRUMENT_DESCRIPTION_INDEXs.size)
      newINSTRUMENT_DESCRIPTION_INDEX(INSTRUMENT_DESCRIPTION_INDEXs(INSTRUMENT_DESCRIPTION_INDEX)) = 1.0

      val newTRADE_MATURITY_DATE_INDEX = new Array[Double](TRADE_MATURITY_DATE_INDEXs.size)
      newTRADE_MATURITY_DATE_INDEX(TRADE_MATURITY_DATE_INDEXs(TRADE_MATURITY_DATE_INDEX)) = 1.0

      val newTRADE_DESCRIPTION_INDEX = new Array[Double](TRADE_DESCRIPTION_INDEXs.size)
      newTRADE_DESCRIPTION_INDEX(TRADE_DESCRIPTION_INDEXs(TRADE_DESCRIPTION_INDEX)) = 1.0

      val newLEGAL_ENTITY_INDEX = new Array[Double](LEGAL_ENTITY_INDEXs.size)
      newLEGAL_ENTITY_INDEX(LEGAL_ENTITY_INDEXs(LEGAL_ENTITY_INDEX)) = 1.0

      val newTRADING_DESK_INDEX = new Array[Double](TRADING_DESK_INDEXs.size)
      newTRADING_DESK_INDEX(TRADING_DESK_INDEXs(TRADING_DESK_INDEX)) = 1.0

      val newSYMBOL_INDEX = new Array[Double](SYMBOL_INDEXs.size)
      newSYMBOL_INDEX(SYMBOL_INDEXs(SYMBOL_INDEX)) = 1.0

      val newTYPE_COL = new Array[Double](TYPE_COLs.size)
      newTYPE_COL(TYPE_COLs(TYPE_COL)) = 1.0

      val newSTATUS_INDEX = new Array[Double](STATUS_INDEXs.size)
      newSTATUS_INDEX(STATUS_INDEXs(STATUS_INDEX)) = 1.0

      val newAUDIT_ACTION_INDEX = new Array[Double](AUDIT_ACTION_INDEXs.size)
      newAUDIT_ACTION_INDEX(AUDIT_ACTION_INDEXs(AUDIT_ACTION_INDEX)) = 1.0

      val newTYPE_CFTR_COL = new Array[Double](TYPE_CFTR_COLs.size)
      newTYPE_CFTR_COL(TYPE_CFTR_COLs(TYPE_CFTR_COL)) = 1.0

      val newCURRENCY_CFTR_INDEX = new Array[Double](CURRENCY_CFTR_INDEXs.size)
      newCURRENCY_CFTR_INDEX(CURRENCY_CFTR_INDEXs(CURRENCY_CFTR_INDEX)) = 1.0

      // Rebuild vector

      //vector.insert(1, TRADE_DATE_INDEX) //0
      vector.insertAll(1, newPRODUCT_TYPE_INDEX) //1
      vector.insertAll(1, newTRADE_ID_INDEX) //2
      vector.insertAll(1, newBOOK_ID_INDEX) //3
      vector.insertAll(1, newCOUNTERPARTY_INDEX) //4
      //vector.insert(1, START_DATE_INDEX) //5
      //vector.insert(1, QUANTITY_INDEX) //6
      vector.insertAll(1, newCURRENCY_INDEX) //7
      vector.insertAll(1, newINSTRUMENT_DESCRIPTION_INDEX) //8
      vector.insertAll(1, newBUY_SELL_INDEX) //9
      vector.insertAll(1, newTRADE_MATURITY_DATE_INDEX) //10
      //vector.insert(1, TRADE_MOD_DATE_INDEX) //11  no se inserta, fecha + hora
      //vector.insert(1, TRADE_VERSION_INDEX) //12
      vector.insertAll(1, newTRADE_DESCRIPTION_INDEX) //13
      //vector.insert(1, EFFECTIVE_DATE_INDEX) //14
      vector.insertAll(1, newLEGAL_ENTITY_INDEX) //15
      vector.insertAll(1, newTRADING_DESK_INDEX) //16
      vector.insertAll(1, newSYMBOL_INDEX) //17
      //vector.insert(1, ORIGINAL_NOTIONAL_INDEX) //18
      //vector.insert(1, NOTIONAL_INDEX) //19
      vector.insertAll(1, newTYPE_COL) //20
      //vector.insert(1, SEC_AMOUNT_INDEX) //21
      vector.insertAll(1, newSTATUS_INDEX) //22
      vector.insertAll(1, newAUDIT_ACTION_INDEX) //23
      vector.insertAll(1, newTYPE_CFTR_COL) //24      
      vector.insertAll(1, newCURRENCY_CFTR_INDEX) //25  
      //vector.insert(1, NOTIONAL_CFTR_INDEX) //26
      //vector.insert(1, REMAINING_NOTIONAL_CFTR_INDEX) //27

      (label, Vectors.dense(vector.toArray))
    }
  }

  def buildLabelFunction(rawData: RDD[String]): (String => (String, Vector)) = {

    val splitData = rawData.map(_.split(",", 28))
    // Variables to categorize.
    //val TRADE_DATE_INDEXs = splitData.map(_(0).toDouble).first
    val PRODUCT_TYPE_INDEXs = splitData.map(_(1)).distinct().collect().zipWithIndex.toMap
    val TRADE_ID_INDEXs = splitData.map(_(2)).distinct().collect().zipWithIndex.toMap
    val BOOK_ID_INDEXs = splitData.map(_(3)).distinct().collect().zipWithIndex.toMap
    val COUNTERPARTY_INDEXs = splitData.map(_(4)).distinct().collect().zipWithIndex.toMap
    //val START_DATE_INDEXs = splitData.map(_(5).toDouble)
    //val QUANTITY_INDEXs = splitData.map(_(6).toDouble)
    val CURRENCY_INDEXs = splitData.map(_(7)).distinct().collect().zipWithIndex.toMap
    val INSTRUMENT_DESCRIPTION_INDEXs = splitData.map(_(8)).distinct().collect().zipWithIndex.toMap
    val BUY_SELL_INDEXs = splitData.map(_(9)).distinct().collect().zipWithIndex.toMap
    val TRADE_MATURITY_DATE_INDEXs = splitData.map(_(10)).distinct().collect().zipWithIndex.toMap
    val TRADE_MOD_DATE_INDEXs = splitData.map(_(11)) // Remove this variable!!! date + time => not signicant value
    //val TRADE_VERSION_INDEXs = splitData.map(_(12).toDouble)
    val TRADE_DESCRIPTION_INDEXs = splitData.map(_(13)).distinct().collect().zipWithIndex.toMap
    //val EFFECTIVE_DATE_INDEXs = splitData.map(_(14).toDouble)
    val LEGAL_ENTITY_INDEXs = splitData.map(_(15)).distinct().collect().zipWithIndex.toMap
    val TRADING_DESK_INDEXs = splitData.map(_(16)).distinct().collect().zipWithIndex.toMap
    val SYMBOL_INDEXs = splitData.map(_(17)).distinct().collect().zipWithIndex.toMap
    //val ORIGINAL_NOTIONAL_INDEXs = splitData.map(_(18).toDouble)
    //val NOTIONAL_INDEXs = splitData.map(_(19).toDouble)
    val TYPE_COLs = splitData.map(_(20)).distinct().collect().zipWithIndex.toMap
    //val SEC_AMOUNT_INDEXs = splitData.map(_(21).toDouble)
    val STATUS_INDEXs = splitData.map(_(22)).distinct().collect().zipWithIndex.toMap
    val AUDIT_ACTION_INDEXs = splitData.map(_(23)).distinct().collect().zipWithIndex.toMap
    val TYPE_CFTR_COLs = splitData.map(_(24)).distinct().collect().zipWithIndex.toMap
    val CURRENCY_CFTR_INDEXs = splitData.map(_(25)).distinct().collect().zipWithIndex.toMap
    //val NOTIONAL_CFTR_INDEXs = splitData.map(_(26).toDouble)
    //val REMAINING_NOTIONAL_CFTR_INDEXs = splitData.map(_(27).toDouble)

    (line: String) => {
      val buffer = line.split(",", 28).toBuffer

      //val TRADE_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val PRODUCT_TYPE_INDEX = buffer.remove(1)
      val TRADE_ID_INDEX = buffer.remove(1)
      val label = TRADE_ID_INDEX
      val BOOK_ID_INDEX = buffer.remove(1)
      val COUNTERPARTY_INDEX = buffer.remove(1)
      //val START_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val QUANTITY_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val CURRENCY_INDEX = buffer.remove(3)
      val INSTRUMENT_DESCRIPTION_INDEX = buffer.remove(3)
      val BUY_SELL_INDEX = buffer.remove(3)
      val TRADE_MATURITY_DATE_INDEX = buffer.remove(3)
      val TRADE_MOD_DATE_INDEX = buffer.remove(3)
      //val TRADE_VERSION_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val TRADE_DESCRIPTION_INDEX = buffer.remove(4)
      //val EFFECTIVE_DATE_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val LEGAL_ENTITY_INDEX = buffer.remove(5)
      val TRADING_DESK_INDEX = buffer.remove(5)
      val SYMBOL_INDEX = buffer.remove(5)
      //val ORIGINAL_NOTIONAL_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val NOTIONAL_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val TYPE_COL = buffer.remove(7)
      //val SEC_AMOUNT_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      val STATUS_INDEX = buffer.remove(8)
      val AUDIT_ACTION_INDEX = buffer.remove(8)
      val TYPE_CFTR_COL = buffer.remove(8)
      val CURRENCY_CFTR_INDEX = buffer.remove(8)
      //val NOTIONAL_CFTR_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble
      //val REMAINING_NOTIONAL_CFTR_INDEX = buffer.remove(0).map(x => if x.Equals("") 0 else x).toDouble

      // None must be left
      // Values = "spaces" get into 0.0 value
      val vector = buffer.map(x => if ("".equals(x)) 0 else x.toDouble)

      //      val newPRODUCT_TYPE_INDEX = new Array[Double](PRODUCT_TYPE_INDEXs.size)
      //      newPRODUCT_TYPE_INDEX(PRODUCT_TYPE_INDEXs(PRODUCT_TYPE_INDEX)) = 1.0
      //
      //      val newTRADE_ID_INDEX = new Array[Double](TRADE_ID_INDEXs.size)
      //      newTRADE_ID_INDEX(TRADE_ID_INDEXs(TRADE_ID_INDEX)) = 1.0
      //
      //      val newBOOK_ID_INDEX = new Array[Double](BOOK_ID_INDEXs.size)
      //      newBOOK_ID_INDEX(BOOK_ID_INDEXs(BOOK_ID_INDEX)) = 1.0
      //
      //      val newBUY_SELL_INDEX = new Array[Double](BUY_SELL_INDEXs.size)
      //      newBUY_SELL_INDEX(BUY_SELL_INDEXs(BUY_SELL_INDEX)) = 1.0
      //
      //      val newCOUNTERPARTY_INDEX = new Array[Double](COUNTERPARTY_INDEXs.size)
      //      newCOUNTERPARTY_INDEX(COUNTERPARTY_INDEXs(COUNTERPARTY_INDEX)) = 1.0
      //
      //      val newCURRENCY_INDEX = new Array[Double](CURRENCY_INDEXs.size)
      //      newCURRENCY_INDEX(CURRENCY_INDEXs(CURRENCY_INDEX)) = 1.0
      //
      //      val newINSTRUMENT_DESCRIPTION_INDEX = new Array[Double](INSTRUMENT_DESCRIPTION_INDEXs.size)
      //      newINSTRUMENT_DESCRIPTION_INDEX(INSTRUMENT_DESCRIPTION_INDEXs(INSTRUMENT_DESCRIPTION_INDEX)) = 1.0
      //
      //      val newTRADE_MATURITY_DATE_INDEX = new Array[Double](TRADE_MATURITY_DATE_INDEXs.size)
      //      newTRADE_MATURITY_DATE_INDEX(TRADE_MATURITY_DATE_INDEXs(TRADE_MATURITY_DATE_INDEX)) = 1.0
      //
      //      val newTRADE_DESCRIPTION_INDEX = new Array[Double](TRADE_DESCRIPTION_INDEXs.size)
      //      newTRADE_DESCRIPTION_INDEX(TRADE_DESCRIPTION_INDEXs(TRADE_DESCRIPTION_INDEX)) = 1.0
      //
      //      val newLEGAL_ENTITY_INDEX = new Array[Double](LEGAL_ENTITY_INDEXs.size)
      //      newLEGAL_ENTITY_INDEX(LEGAL_ENTITY_INDEXs(LEGAL_ENTITY_INDEX)) = 1.0
      //
      //      val newTRADING_DESK_INDEX = new Array[Double](TRADING_DESK_INDEXs.size)
      //      newTRADING_DESK_INDEX(TRADING_DESK_INDEXs(TRADING_DESK_INDEX)) = 1.0
      //
      //      val newSYMBOL_INDEX = new Array[Double](SYMBOL_INDEXs.size)
      //      newSYMBOL_INDEX(SYMBOL_INDEXs(SYMBOL_INDEX)) = 1.0
      //
      //      val newTYPE_COL = new Array[Double](TYPE_COLs.size)
      //      newTYPE_COL(TYPE_COLs(TYPE_COL)) = 1.0
      //
      //      val newSTATUS_INDEX = new Array[Double](STATUS_INDEXs.size)
      //      newSTATUS_INDEX(STATUS_INDEXs(STATUS_INDEX)) = 1.0
      //
      //      val newAUDIT_ACTION_INDEX = new Array[Double](AUDIT_ACTION_INDEXs.size)
      //      newAUDIT_ACTION_INDEX(AUDIT_ACTION_INDEXs(AUDIT_ACTION_INDEX)) = 1.0
      //
      //      val newTYPE_CFTR_COL = new Array[Double](TYPE_CFTR_COLs.size)
      //      newTYPE_CFTR_COL(TYPE_CFTR_COLs(TYPE_CFTR_COL)) = 1.0
      //
      //      val newCURRENCY_CFTR_INDEX = new Array[Double](CURRENCY_CFTR_INDEXs.size)
      //      newCURRENCY_CFTR_INDEX(CURRENCY_CFTR_INDEXs(CURRENCY_CFTR_INDEX)) = 1.0

      // Rebuild vector

      //      //vector.insert(1, TRADE_DATE_INDEX) //0
      //      vector.insertAll(1, newPRODUCT_TYPE_INDEX) //1
      //      vector.insertAll(1, newTRADE_ID_INDEX) //2
      //      vector.insertAll(1, newBOOK_ID_INDEX) //3
      //      vector.insertAll(1, newCOUNTERPARTY_INDEX) //4
      //      //vector.insert(1, START_DATE_INDEX) //5
      //      //vector.insert(1, QUANTITY_INDEX) //6
      //      vector.insertAll(1, newCURRENCY_INDEX) //7
      //      vector.insertAll(1, newINSTRUMENT_DESCRIPTION_INDEX) //8
      //      vector.insertAll(1, newBUY_SELL_INDEX) //9
      //      vector.insertAll(1, newTRADE_MATURITY_DATE_INDEX) //10
      //      //vector.insert(1, TRADE_MOD_DATE_INDEX) //11  no se inserta, fecha + hora
      //      //vector.insert(1, TRADE_VERSION_INDEX) //12
      //      vector.insertAll(1, newTRADE_DESCRIPTION_INDEX) //13
      //      //vector.insert(1, EFFECTIVE_DATE_INDEX) //14
      //      vector.insertAll(1, newLEGAL_ENTITY_INDEX) //15
      //      vector.insertAll(1, newTRADING_DESK_INDEX) //16
      //      vector.insertAll(1, newSYMBOL_INDEX) //17
      //      //vector.insert(1, ORIGINAL_NOTIONAL_INDEX) //18
      //      //vector.insert(1, NOTIONAL_INDEX) //19
      //      vector.insertAll(1, newTYPE_COL) //20
      //      //vector.insert(1, SEC_AMOUNT_INDEX) //21
      //      vector.insertAll(1, newSTATUS_INDEX) //22
      //      vector.insertAll(1, newAUDIT_ACTION_INDEX) //23
      //      vector.insertAll(1, newTYPE_CFTR_COL) //24      
      //      vector.insertAll(1, newCURRENCY_CFTR_INDEX) //25  
      //      //vector.insert(1, NOTIONAL_CFTR_INDEX) //26
      //      //vector.insert(1, REMAINING_NOTIONAL_CFTR_INDEX) //27

      (label, Vectors.dense(vector.toArray))
    }
  }

  // Detect anomalies

  def buildAnomalyDetector(
    data: RDD[Vector],
    normalizeFunction: (Vector => Vector)): (Vector => Boolean) = {
    val normalizedData = data.map(normalizeFunction)
    normalizedData.cache()

    val kmeans = new KMeans()
    kmeans.setK(150)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(normalizedData)

    normalizedData.unpersist()

    val distances = normalizedData.map(datum => distToCentroid(datum, model))
    val threshold = distances.top(100).last

    (datum: Vector) => distToCentroid(normalizeFunction(datum), model) > threshold
  }

  def anomalies(rawData: RDD[String]) = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val originalAndData = rawData.map(line => (line, parseFunction(line)._2))
    val data = originalAndData.values
    val normalizeFunction = buildNormalizationFunction(data)
    val anomalyDetector = buildAnomalyDetector(data, normalizeFunction)
    val anomalies = originalAndData.filter {
      case (original, datum) => anomalyDetector(datum)
    }.keys
    println("ANOMALIES")
    //anomalies.take(10).foreach(println)

    val outputDir = "ds/"
    anomalies.take(10).foreach { x =>
      //val dateString = Calendar.getInstance().getTime.toString.replace(" ", "-").replace(":", "-")
      printToFile(outputDir, "anomalies-full", x)
    }
  }

  def printToFile(pathName: String, fileName: String, contents: String) = {
    val file = new File(pathName + "/" + fileName + ".txt")
    if (!file.exists()) {
      file.createNewFile()
    }

    val bw = new BufferedWriter(new FileWriter(file, true))
    bw.write(contents)
    bw.newLine()
    bw.close()
  }

  // Returns estimated value for K given a Vector
  def stats4K(dataRDD: RDD[Vector]): Int = {

    // Rule of Thumb
    // k aprox = (n/2)^0.5
    val estimatedValue = (sqrt(dataRDD.count() / 2)).toInt
    // No es metodo fiable, ya que cada vez que se ejecuta da una solucion distinta.
    // Compute the WSS
    // Siempre tendria que dar el mismo valor, y no lo hace
    
    
//    val parKV = (1 to estimatedValue by 1).map(k => (k, clusteringScoreWSS(dataRDD, k)))
//    val vector1 = parKV.toIndexedSeq.map(f => f._2).toList
//    val vector2 = vector1.drop(1)
//    val diffTwoVector = vector1.zip(vector2).map(t => abs(t._1 - t._2))
//    val minValue = diffTwoVector.min
//    val indexKey = diffTwoVector.toList.indexOf(minValue)
//    val outputDir = "ds/"
//    vector1.toList.foreach { x =>
//      printToFile(outputDir, "vector1-full", x.toString())
//    }
//    vector2.toList.foreach { x =>
//      printToFile(outputDir, "vector2-full", x.toString())
//    }
//    diffTwoVector.toList.foreach { x =>
//      printToFile(outputDir, "diffTwoVector-full", x.toString())
//    }

//    indexKey
    estimatedValue
  }

  //-------------------------------------------------------------------------
  // Compute the WSS
  def clusteringScoreWSS(data: RDD[Vector], k: Int): Double = {
    val clusters = KMeans.train(data, k, 1)
    val WSS = clusters.computeCost(data)

    WSS
  }
}