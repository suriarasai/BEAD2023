package regression

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.mllib.evaluation.RegressionMetrics
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.regression.LinearRegressionWithSGD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.col

import org.apache.spark.sql.functions.concat
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.functions.mean
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.DoubleType


object FeatureEngg {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster("local[2]")
      .setAppName("Sales_Prediction")
    val sc = new SparkContext(conf)
    sc.setLogLevel("ERROR")
    val sqlContext = new SQLContext(sc)
    import sqlContext.implicits._
    //Loading data
    val sales_data_train =
      sqlContext.read.format("com.databricks.spark.csv")
        .option("header", "true")
        .option("inferSchema", "true")
        .load("/home/cloudera/git/BEAD2020/06-SparkML/data/regression/Sales_Train.csv")
    val sales_data_test =
      sqlContext.read.format("com.databricks.spark.csv")
        .option("header", "true")
        .option("inferSchema", "true")
        .load("/home/cloudera/git/BEAD2020/06-SparkML/data/regression/Sales_Test.csv")
    val sales_data_union = sales_data_train.unionAll(sales_data_test)
    val sales_data = sales_data_union.withColumn(
      "Item_Outlet_Sales",
      sales_data_union.col("Item_Outlet_Sales").cast(DoubleType))

    // Basic Statistics for numerical variables
    val summary_stats = sales_data.describe()
    println("Summary Statistics")
    summary_stats.show()
    // Unique values in each field
    val columnNames = sales_data.columns
    val uniqueValues_PerField = columnNames.map {
      fieldName =>
        fieldName + ":" +
          sales_data.select(fieldName).distinct().count.toString
    }
    uniqueValues_PerField.foreach(println)
    //Frequency of Categories for categorical variables
    val frequency_Variables = columnNames.map {
      fieldName =>
        if (fieldName == "Item_Fat_Content" || fieldName ==
          "Item_Type" || fieldName ==
          "Outlet_Size" || fieldName == "Outlet_Location_Type" ||
          fieldName == "Outlet_Type")
          Option(fieldName, sales_data.groupBy(fieldName).count())
        else None
    }
    val seq_Df_WithFrequencyCount =
      frequency_Variables.filter(optionalDf =>
        optionalDf != None).map { optionalDf => optionalDf.get }
    seq_Df_WithFrequencyCount.foreach {
      case (fieldName, df) =>
        println("Frequency Count of " + fieldName)
        df.show()
    }

    // Replace Missing values for Item_Weight
    val df_Item_WeightNull =
      sales_data.filter(sales_data("Item_Weight").isNull)
    println("Missing rows for Item_Weight: " + df_Item_WeightNull.count)
    val mean_ItemWeight = sales_data.select(mean("Item_Weight"))
      .first()(0).asInstanceOf[Double]
    val fill_MissingValues_ItemWeight =
      sales_data.na.fill(mean_ItemWeight, Seq("Item_Weight"))
    println("After replacing the mean for Item_Weight..." +
      fill_MissingValues_ItemWeight.filter(fill_MissingValues_ItemWeight("Item_Weight").isNull).count)
    //Replace missing values for Outlet_Size with the mode ofOutlet_Size
    val df_OutletSizeNull =
      fill_MissingValues_ItemWeight.filter(fill_MissingValues_ItemWeight("Outlet_Size").like(""))
    println("Missing Outlet Size Rows: " + df_OutletSizeNull.count)
    val new_Df =
      fill_MissingValues_ItemWeight.withColumn(
        "Outlet_Type_Size",
        concat($"Outlet_Type", lit(":"), $"Outlet_Size"))
    val aggregated_Df = new_Df.groupBy("Outlet_Type_Size").count()
    val modified_Df = new_Df.na.replace("Outlet_Size", Map("" -> "NA"))
    modified_Df.show()
    val df_SuperMarketType1 =
      modified_Df.filter(new_Df("Outlet_Type").contains("SupermarketType1"))
    val df_SuperMarketType2 =
      modified_Df.filter(new_Df("Outlet_Type").contains("Supermarket Type2"))
    val df_SuperMarketType3 =
      modified_Df.filter(new_Df("Outlet_Type").contains("SupermarketType3"))
    val df_GroceryStore =
      modified_Df.filter(new_Df("Outlet_Type").contains("Grocery Store"))
    val replacedMissingValues_ForOutletSize_With_SuperMarketType1 =
      df_SuperMarketType1.na.replace("Outlet_Size", Map("NA" -> "Small"))

    val replacedMissingValues_ForOutletSize_With_SuperMarketType2 =
      df_SuperMarketType2.na.replace("Outlet_Size", Map("NA" ->
        "Medium"))
    val replacedMissingValues_ForOutletSize_With_SuperMarketType3 =
      df_SuperMarketType3.na.replace("Outlet_Size", Map("NA" ->
        "Medium"))
    val replacedMissingValues_ForOutletSize_With_GroceryStore = df_GroceryStore.na.replace("Outlet_Size", Map("NA" -> "Small"))
    val replaced_MissingValues_ForOutletSize = replacedMissingValues_ForOutletSize_With_SuperMarketType1
      .unionAll(replacedMissingValues_ForOutletSize_With_SuperMarketType2)
      .unionAll(replacedMissingValues_ForOutletSize_With_SuperMarketType3)
      .unionAll(replacedMissingValues_ForOutletSize_With_GroceryStore)

    val missing_Rows = replaced_MissingValues_ForOutletSize
      .filter(replaced_MissingValues_ForOutletSize("Outlet_Size").like(""))
    println("After replacing the mode for missing values of Outlet Size: " + missing_Rows.count)

    // Removing the target variable 'Item_Outlet_Sales' from the data
    val sales_Data_WithoutOutletSales = sales_data.drop("Item_Outlet_Sales")
    // Exclude ID variables
    val sales_Data_Refined = sales_Data_WithoutOutletSales.drop("Item_Identifier").drop("Outlet_Identifier")

    // Replace Missing values for Item_Visibility
    val df_Item_VisibilityNull =
      replaced_MissingValues_ForOutletSize.filter(sales_Data_Refined("Item_Visibility") === 0)
    println("Missing rows for Item_Visibility: " + df_Item_VisibilityNull.count)
    val mean_ItemVisibility = replaced_MissingValues_ForOutletSize
      .select(mean("Item_Visibility")).first()(0).asInstanceOf[Double]
    val df_ForFeatureEngg =
      replaced_MissingValues_ForOutletSize.na.replace("Item_Visibility", Map(0.0 -> mean_ItemVisibility))
    println("After replacing the mean for Item_Visibility ..." + df_ForFeatureEngg.filter(df_ForFeatureEngg("Item_Visibility") === 0).count)

    val df_MeanSales_PerOutletType = df_ForFeatureEngg.groupBy("Outlet_Type").mean("Item_Outlet_Sales")
    println("Mean Sales Per Outlet_Type")
    df_MeanSales_PerOutletType.show()

    val sqlFunc = udf(coder)
    val new_Df_WithItemTypeCombined = df_ForFeatureEngg
      .withColumn("Item_Type_Combined", sqlFunc(col("Item_Identifier")))
    new_Df_WithItemTypeCombined.groupBy("Item_Type_Combined").count().show()

    val sqlFunc2 = udf(coder2)
    val revised_Df = new_Df_WithItemTypeCombined.withColumn(
      "Outlet_Years",
      sqlFunc2(col("Outlet_Establishment_Year")))
    revised_Df.select("Outlet_Years").describe().show()

    val sqlFunc3 = udf(coder3)
    val new_Df_WithItem_Fat_Content_Combined = revised_Df
      .withColumn("Item_Fat_Content_Combined", sqlFunc(col("Item_Fat_Content")))
    new_Df_WithItem_Fat_Content_Combined
      .groupBy("Item_Fat_Content_Combined").count().show()

    // Exclude ID variable
    val sales_Data_new = new_Df_WithItem_Fat_Content_Combined
      .drop("Item_Identifier").drop("Outlet_Identifier")
    // Removing unused Columns
    val sales_Data_Final = sales_Data_new
      .drop("Item_Fat_Content").drop("Item_Type").drop("Outlet_Type_Size")
      .drop("Outlet_Establishment_Year")

    /* Applying One Hot encoding of Categorical Variables */
    val sqlFunc_CreateDummyVariables = udf(udf_returnDummyValues)
    // One Hot Encoding for Outlet_Type
    val new_Df_WithDummy_OutletType =
      create_DummyVariables(
        sales_Data_Final,
        sqlFunc_CreateDummyVariables, "Outlet_Type", 0)
    // One Hot Encoding for Outlet_Size
    val new_Df_WithDummy_OutletSize =
      create_DummyVariables(
        new_Df_WithDummy_OutletType,
        sqlFunc_CreateDummyVariables, "Outlet_Size", 0)
    // One Hot Encoding for Outlet_Location_Type
    val new_Df_WithDummy_OutletLocationType =
      create_DummyVariables(
        new_Df_WithDummy_OutletSize,
        sqlFunc_CreateDummyVariables, "Outlet_Location_Type", 0)
    // One Hot Encoding for Item_Type_Combined
    val new_Df_WithDummy_ItemTypeCombined =
      create_DummyVariables(
        new_Df_WithDummy_OutletLocationType,
        sqlFunc_CreateDummyVariables, "Item_Type_Combined", 0)
    // One Hot Encoding for Item_Fat_Content_Combined
    val new_Df_WithDummy_ItemFatContentCombined =
      create_DummyVariables(
        new_Df_WithDummy_ItemTypeCombined,
        sqlFunc_CreateDummyVariables, "Item_Fat_Content_Combined", 0)

    //Remove categorical columns
    val final_Df = new_Df_WithDummy_ItemFatContentCombined
      .drop("Outlet_Size")
      .drop("Outlet_Location_Type")
      .drop("Outlet_Type")
      .drop("Item_Type_Combined")
      .drop("Item_Fat_Content_Combined")
    final_Df.show(5)

    // Applying Linear Regression
    val train_Df =
      final_Df.filter(final_Df("Item_Outlet_Sales").isNotNull)
    val test_Df =
      final_Df.filter(final_Df("Item_Outlet_Sales").isNull)
    val train_Rdd = train_Df.rdd.map {
      row =>
        val item_weight = row.getAs[Double]("Item_Weight")
        val item_Visibility = row.getAs[Double]("Item_Visibility")
        val item_mrp = row.getAs[Double]("Item_MRP")
        val item_outlet_sales = row.getAs[Double]("Item_Outlet_Sales")
        val otlet_years = row.getAs[Int]("Outlet_Years").toDouble
        val outlet_type_0 = row.getAs[Double]("Outlet_Type_0")
        val outlet_type_1 = row.getAs[Double]("Outlet_Type_1")

        val outlet_type_2 = row.getAs[Double]("Outlet_Type_2")
        val outlet_type_3 = row.getAs[Double]("Outlet_Type_3")
        val outlet_size_0 = row.getAs[Double]("Outlet_Size_0")
        val outlet_size_1 = row.getAs[Double]("Outlet_Size_1")
        val outlet_size_2 = row.getAs[Double]("Outlet_Size_2")
        val outlet_Location_Type_0 = row.getAs[Double]("Outlet_Location_Type_0")
        val outlet_Location_Type_1 = row.getAs[Double]("Outlet_Location_Type_1")
        val outlet_Location_Type_2 = row.getAs[Double]("Outlet_Location_Type_2")
        val item_type_0 = row.getAs[Double]("Item_Type_Combined_0")
        val item_type_1 = row.getAs[Double]("Item_Type_Combined_1")
        val item_type_2 = row.getAs[Double]("Item_Type_Combined_2")
        val item_fat_content_0 = row.getAs[Double]("Item_Fat_Content_Combined_0")
        val item_fat_content_1 = row.getAs[Double]("Item_Fat_Content_Combined_1")
        val featurecVec = Vectors.dense(Array(
          item_weight,
          item_Visibility, item_mrp, otlet_years, outlet_type_0,
          outlet_type_1, outlet_type_2, outlet_type_3, outlet_size_0,
          outlet_size_1, outlet_size_2, outlet_Location_Type_0,
          outlet_Location_Type_1, outlet_Location_Type_2,
          item_type_0, item_type_1, item_type_2, item_fat_content_0,
          item_fat_content_1))
        LabeledPoint(item_outlet_sales, featurecVec)
    }.cache()
    val numIters = 500
    val stepSize = 0.0001
    // Applying the linear Regression Model
    val lm = new LinearRegressionWithSGD().setIntercept(true)
    lm.optimizer.setStepSize(stepSize)
    lm.optimizer.setNumIterations(numIters)
    lm.optimizer.setMiniBatchFraction(0.2)
    lm.optimizer.setConvergenceTol(0.0001)
    lm.optimizer.setRegParam(0.1)
    val model = lm.run(train_Rdd)
    val predictedData = train_Rdd.map {
      labeledPoint =>
        val featureVec = labeledPoint.features
        val originalValue = labeledPoint.label
        val predictedValue = model.predict(featureVec)
        (originalValue, predictedValue)
    }
    val mse = predictedData.map {
      case (original, predicted) =>
        (original - predicted) * (original - predicted)
    }.mean()
    val metricsObject = new RegressionMetrics(predictedData)
    println("R2 Value: " + metricsObject.r2)
    println("Mean Squared Error: " + mse)

  }
  val coder = (id: String) => id.substring(0, 2) match {
    case "FD" => "Food"
    case "NC" => "Non-Consumable"
    case "DR" => "Drinks"
  }
  val coder2 = (value: Int) => 2013 - value
  val coder3 = (id: String) => id match {
    case "LF" | "Low Fat"  => "Low Fat"
    case "reg" | "Regular" => "Regular"
    case "low fat"         => "Low Fat"
  }

  def create_DummyVariables(
    df:DataFrame,
    udf_Func:UserDefinedFunction,
    variableType: String, i: Int): DataFrame = {
    variableType match {
      case "Outlet_Type" => if (i == 4) df
      else {
        val df_new = df.withColumn(
          variableType + "_" + i.toString,
          udf_Func(lit(variableType), col("Outlet_Type"), lit(i)))
        create_DummyVariables(df_new, udf_Func, variableType, i + 1)
      }
      case "Outlet_Size" => if (i == 3) df
      else {
        val df_new = df.withColumn(
          variableType + "_" + i.toString,
          udf_Func(lit(variableType), col("Outlet_Size"), lit(i)))
        create_DummyVariables(df_new, udf_Func, variableType, i + 1)
      }
      case "Outlet_Location_Type" => if (i == 3) df
      else {
        val df_new = df.withColumn(
          variableType + "_" + i.toString,
          udf_Func(lit(variableType), col("Outlet_Location_Type"),
            lit(i)))
        create_DummyVariables(df_new, udf_Func, variableType, i + 1)
      }
      case "Item_Type_Combined" => if (i == 3) df
      else {
        val df_new = df.withColumn(
          variableType + "_" + i.toString,
          udf_Func(lit(variableType), col("Item_Type_Combined"),
            lit(i)))
        create_DummyVariables(df_new, udf_Func, variableType, i + 1)
      }
      case "Item_Fat_Content_Combined" => if (i == 2) df
      else {
        val df_new = df.withColumn(
          variableType + "_" + i.toString,
          udf_Func(lit(variableType), col("Item_Fat_Content_Combined"),
            lit(i)))
        create_DummyVariables(df_new, udf_Func, variableType, i + 1)
      }
    }
  }
  val udf_returnDummyValues = (variableType: String,
    columnValue: String,
    jobNo: Int) => variableType match {
    case "Outlet_Type" => columnValue match {
      case "Grocery Store" => if (jobNo == 0) 1.0 else 0.0
      case "Supermarket Type1" => if (jobNo == 1) 1.0
      else 0.0
      case "Supermarket Type2" => if (jobNo == 2) 1.0
      else 0.0
      case "Supermarket Type3" => if (jobNo == 3) 1.0
      else 0.0
    }
    case "Outlet_Size" => columnValue match {
      case "High"   => if (jobNo == 0) 1.0 else 0.0
      case "Small"  => if (jobNo == 1) 1.0 else 0.0
      case "Medium" => if (jobNo == 2) 1.0 else 0.0
    }
    case "Outlet_Location_Type" => columnValue match {
      case "Tier 1" => if (jobNo == 0) 1.0 else 0.0
      case "Tier 2" => if (jobNo == 1) 1.0 else 0.0
      case "Tier 3" => if (jobNo == 2) 1.0 else 0.0
    }
    case "Item_Type_Combined" => columnValue match {
      case "Drinks"         => if (jobNo == 0) 1.0 else 0.0
      case "Food"           => if (jobNo == 1) 1.0 else 0.0
      case "Non-Consumable" => if (jobNo == 2) 1.0 else 0.0
    }
    case "Item_Fat_Content_Combined" => columnValue match {
      case "Low Fat" => if (jobNo == 0) 1.0 else 0.0
      case "Regular" => if (jobNo == 1) 1.0 else 0.0
    }
  }
}
