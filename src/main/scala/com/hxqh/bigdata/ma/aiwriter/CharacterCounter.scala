package com.hxqh.bigdata.ma.aiwriter

import java.util

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.util.ElasticSearchUtils
import org.apache.spark.sql.SparkSession

/**
  * Created by Ocean lin on 2018/3/22.
  *
  * @author Ocean lin
  */
object CharacterCounter {

  def main(args: Array[String]): Unit = {
    //  val spark = SparkSession.builder.appName("CharacterCounter").getOrCreate
    val spark = SparkSession.builder.master("local").appName("CharacterCounter").getOrCreate
//    registerESTable(spark, "telescript", "telescript_data", "telescript")
    registerESTable(spark, "telescript", "screenplay_data", "screenplay")

    val sql = "select word_count from telescript "
    val telescript = spark.sql(sql).rdd
    telescript.cache
    val client = ElasticSearchUtils.getClient

    val sum_word = telescript.map(e => e.getString(0).toInt).sum()
    println("The sum word is: " + sum_word)


  }

  /**
    * 获取ElasticSearch中的索引注册为表
    *
    * @param spark     SparkSession
    * @param tableName 临时表名称
    * @param indexName index名称
    * @param typeName  type名称
    */
  private def registerESTable(spark: SparkSession, tableName: String, indexName: String, typeName: String): Unit = {
    val esOptions = new util.HashMap[String, String](3)
    esOptions.put("es.nodes", "ubuntu3")
    esOptions.put("es.port", Constants.ES_PORT_STRING)
    esOptions.put("es.mapping.date.rich", "false")
    esOptions.put("es.index.auto.create", "true")
    val dataset = spark.read.format("org.elasticsearch.spark.sql").options(esOptions).load(indexName + "/" + typeName)
    dataset.createOrReplaceTempView(tableName)
  }


}
