package com.hxqh.bigdata.ma.spark

import java.io.IOException
import java.util

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.domain.Variety
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, SparkUtil}
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

/**
  * Created by Ocean lin on 2018/3/22.
  *
  * @author Ocean lin
  */
object MarketVarietySpark {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("MarketVarietySpark").getOrCreate
    //    val spark = SparkSession.builder.master("local").appName("MarketVarietySpark").getOrCreate
    registerESTable(spark, "film", "film_data", "film")
    val startDate = DateUtils.getYesterdayDate()
    val endDate = DateUtils.getTodayDate()
    //    val startDate = DateUtils.getTodayDate()
    //    val endDate = DateUtils.getTomorrow()


    val sql = "select * from Film  where  category = 'variety'  and addTime >='" + startDate + "' and addTime <= '" + endDate + "'"
    val variety = spark.sql(sql).rdd
    variety.cache
    val client = ElasticSearchUtils.getClient

    // [2018-03-20 10:45:36,variety,0,null,：医学泰斗寻找长寿秘诀 馒头白水竟是百岁老人的日常食谱,内地 其它,101000,0.0,iqiyi,刘婧,27]
    // 播放量Top10
    var i = 1
    variety.distinct().filter(e => (e.getInt(6) != null)).filter(e => (e.getString(4) != null))
      .map(e => (e.getInt(6), e.getString(4))).sortByKey(false).take(Constants.VARIETY_TOP_NUM)
      .foreach(e => {
        val variety = new Variety(e._1.toDouble, e._2, Constants.VARIETY_PLAYNUM, i)
        addvariety(variety, client)
        i = i + 1
      })

    // 分类占比
    i = 1
    variety.distinct().filter(e => (e.getString(5) != null)).flatMap(e => {
      val splits = e.getString(5).split(" ")
      for (x <- 0 until splits.length - 1)
        yield (splits(x), 1)
    }).reduceByKey(_ + _).filter(e => e._2 > 10).collect().foreach(e => {
      val variety = new Variety(e._2.toDouble, e._1, Constants.VARIETY_LABEL_PIE, i)
      addvariety(variety, client)
      i = i + 1
    })

    // 播放量前10的分类占比
    i = 1
    variety.distinct().filter(e => (null != e.get(5))).filter(e => (e.getString(5) != null)).flatMap(e => {
      val splits = e.getString(5).split(" ")
      for (x <- 0 until splits.length - 1)
        yield (splits(x), e.getInt(6))
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(Constants.VARIETY_TOP_NUM)
      .foreach(e => {
        val variety = new Variety(e._1.toDouble, e._2, Constants.VARIETY_LABEL_PLAYNUM_PIE, i)
        addvariety(variety, client)
        i = i + 1
      })

    // 播放量最多嘉宾Top10
    i = 1
    variety.distinct().filter(e => (null != e.get(6))).filter(e => (null != e.get(9))).flatMap(e => {
      val splits = e.getString(9).split(" ")
      for (x <- 0 until splits.length - 1)
        yield (splits(x), e.getInt(6))
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(Constants.VARIETY_TOP_NUM).foreach(e => {
      val variety = new Variety(e._1.toDouble, e._2, Constants.VARIETY_GUEST_PALYNUM, i)
      addvariety(variety, client)
      i = i + 1
    })


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
    val esOptions: util.Map[String, String] = SparkUtil.initOption
    val dataset: Dataset[Row] = spark.read.format("org.elasticsearch.spark.sql").options(esOptions).load(indexName + "/" + typeName)
    dataset.createOrReplaceTempView(tableName)
  }

  /**
    *
    * @param variety 持久化的综艺节目对象
    * @param client  elasticsearch client
    */
  def addvariety(variety: Variety, client: TransportClient): Unit = try {
    val todayTime = DateUtils.getTodayTime
    val content = XContentFactory.jsonBuilder.startObject.
      field("numvalue", variety.numvalue).
      field("name", variety.name).
      field("category", variety.category).
      field("indexNumber", variety.indexNumber).
      field("addTime", todayTime).endObject

    client.prepareIndex(Constants.FILM_INDEX, Constants.FILM_TYPE).setSource(content).get
    println(variety.name + " Persist to ES Success!")
  } catch {
    case e: IOException =>
      e.printStackTrace()
  }
}
