package regression

import org.apache.spark.sql.SQLContext
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.functions.concat
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.functions.mean

object DataExploration {
  
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
  }

}
