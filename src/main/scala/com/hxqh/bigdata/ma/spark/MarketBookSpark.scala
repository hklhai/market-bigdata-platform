package com.hxqh.bigdata.ma.spark

import java.io.IOException
import java.util

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.domain.Books
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, SparkUtil}
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

/**
  * Created by Ocean lin on 2018/3/22.
  *
  * @author Ocean lin
  */
object MarketBookSpark {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("MarketBookSpark").getOrCreate
    registerESTable(spark, "book", "market_book2", "book")
    val startDate = DateUtils.getYesterdayDate()
    val endDate = DateUtils.getTodayDate()

    val sql = "select * from book where addTime>='" + startDate + "' and addTime<= '" + endDate + "'"
    val book = spark.sql(sql).rdd
    book.cache
    val client = ElasticSearchUtils.getClient


    //  2018-03-21 18:01:07,黄成明,数据化管理：洞悉零售及电子商务运营,计算机与互联网,计算机与互联网 电子商务 Broadview 数据化管理：洞悉零售及电子商务运营,6700,42.2,电子工业出版社,jd
    //  累计评论量排名Top10
    book.distinct().map(e => (e.getString(2), e.getLong(5))).reduceByKey(_ + _).map(e => (e._2, e._1)).
      sortByKey(false).take(Constants.BOOK_TOP_NUM).foreach(e => {
      addBook(new Books(e._1.toDouble, e._2, Constants.BOOKS_COMMENT), client)
    })

    // 各类别占比情况
    book.distinct().flatMap(e => {
      val splits = e.getString(4).replaceAll("/", " ").replaceAll(",", " ").split(" ")
      for (i <- 0 until splits.length)
        yield (splits(i), 1)
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(15).foreach(e => {
      addBook(new Books(e._1.toDouble, e._2, Constants.BOOKS_LABEL), client)
    })

    // 累计评论量最多出版社排名Top10
    book.distinct().map(e => (e.getString(7), e.getLong(5))).reduceByKey(_ + _).map(e => (e._2, e._1)).
      sortByKey(false).take(Constants.BOOK_TOP_NUM).foreach(e => {
      addBook(new Books(e._1.toDouble, e._2, Constants.BOOKS_PRESS), client)
    })


    // todo
    // 豆瓣评分Top10
    // 豆瓣评分人数Top10


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
    * @param book   持久化的book对象
    * @param client elasticsearch client
    */
  def addBook(book: Books, client: TransportClient): Unit = try {
    val todayTime = DateUtils.getTodayTime
    val content = XContentFactory.jsonBuilder.startObject.
      field("numvalue", book.numvalue).
      field("name", book.name).
      field("category", book.category).
      field("addTime", todayTime).endObject

    client.prepareIndex(Constants.BOOKS_ANALYSIS_INDEX, Constants.BOOKS_ANALYSIS_TYPE).setSource(content).get
    println(book.name + " Persist to ES Success!")
  } catch {
    case e: IOException =>
      e.printStackTrace()
  }

}
