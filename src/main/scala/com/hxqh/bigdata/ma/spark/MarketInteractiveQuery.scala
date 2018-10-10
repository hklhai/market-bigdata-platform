package com.hxqh.bigdata.ma.spark

import java.io.IOException
import java.util.Date

import com.alibaba.fastjson.JSON
import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.dao.{DaoFactory, TaskDao}
import com.hxqh.bigdata.ma.domain.Show
import com.hxqh.bigdata.ma.model.Task
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, EsUtils}
import org.apache.spark.sql.SparkSession
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

import scala.collection.mutable
import scala.util.control.Breaks._

/**
  * Created by Ocean lin on 2018/4/8.
  *
  * @author Ocean lin
  */
object MarketInteractiveQuery extends Serializable {

  def main(args: Array[String]): Unit = {

    val client = ElasticSearchUtils.getClient
    //    val spark = SparkSession.builder.master("local").appName("MarketInteractiveQuery").getOrCreate
    val spark = SparkSession.builder.appName("MarketInteractiveQuery").getOrCreate

    val indexMap = Map(
      "soap" -> "film_data/film",
      "film" -> "film_data/film",
      "variety" -> "film_data/film",
      "maoyan" -> "maoyan/film",
      "book" -> "market_book2/book",
      "literature" -> "market_literature/literature"
    )

    while (true) {
      // 获取MySQL信息
      val taskDao = DaoFactory.getTaskDAO
      val taskList = taskDao.findAll()
      breakable {
        if (taskList.size() == 0) {
          Thread.sleep(Constants.TEN_SECOND)
          // println("Spark作业监控中 " + (new Date()).toString)
          break
        } else {
          val task = taskList.get(0)
          val taskParameter: String = task.getTaskParam
          val taskJSON = JSON.parseObject(taskParameter)
          val startDate = taskJSON.getString(Constants.PARAM_START_DATE)
          val endDate = taskJSON.getString(Constants.PARAM_END_DATE)
          val category = taskJSON.getString(Constants.PARAM_CATEGORY)
          val title = taskJSON.getString(Constants.PARAM_TITLE)
          // 更新状态 正在运行
          persistStatus(task.getTaskid, Constants.RUNNING, taskDao)
          val indexName = indexMap(category).split("/")(0)
          val typeName = indexMap(category).split("/")(1)

          // 更新Spark作业启动时间
          taskDao.updateStartTime(new Date(), task.getTaskid)
          try {

            // 电影、综艺、电视剧
            analysisFilmSoapVariety(client, spark, taskDao, task, startDate, endDate, category, title, indexName, typeName)

            // 图书
            analysisBook(client, spark, taskDao, task, startDate, endDate, category, title, indexName, typeName)

            // 网络文学
            analysisLiterature(client, spark, taskDao, task, startDate, endDate, category, title, indexName, typeName)

            // 猫眼 统计各电影票房占比，不同于图书评论量、电影电视剧综艺播放量、网路文学点击量
            if (category.equals("maoyan")) {
              EsUtils.registerESTable(spark, "maoyan", indexName, typeName)

              val sql = "select boxInfo,addTime from maoyan where filmName = '" + title + "' and  addtime>='" +
                startDate + "' and addtime<= '" + endDate + "' order by addTime desc limit 7"
              val maoyan = spark.sql(sql)
              val maoyanRDD = maoyan.rdd.collect()
              if (maoyanRDD.size == 0) {
                persistStatus(task.getTaskid, Constants.NODATA, taskDao)
              } else {
                maoyanRDD.foreach(e => {
                  val show = new Show(e.getFloat(0).toDouble, e.get(1).toString, "line", task.getTaskid, "")
                  addShow(show, client)
                })

                // 计算占比
                val sqlPie = "select filmName,boxInfo from maoyan where addtime>='" +
                  startDate + "' and addtime<= '" + endDate + "' order by addTime desc limit 7"
                val maoyanPieRDD = spark.sql(sqlPie).rdd
                val allPieRDD = maoyanPieRDD.map(e => {
                  (e.getString(0), e.getFloat(1))
                }).reduceByKey(_ + _).collect()

                allPieRDD.foreach(e => {
                  val show = new Show(e._2.toDouble, null, "pie", task.getTaskid, e._1.toString)
                  addShow(show, client)
                })

                // 更新状态 完成
                persistStatus(task.getTaskid, Constants.FINISH, taskDao)
              }
            }

          } catch {
            case ex: Exception => persistStatusWithException(task.getTaskid, Constants.TASKFAIL, taskDao, ex.toString)
          }
          // 更新Spark作业完成时间
          taskDao.updateFinishTime(new Date(), task.getTaskid)

        } //end if
      }
    }
  }

  /**
    *
    * @param taskId 任务标识
    * @param status 任务状态
    */
  private def persistStatus(taskId: Any, status: String, taskDao: TaskDao) = {
    val task = new Task()
    task.setTaskid(taskId.asInstanceOf[Long])
    task.setTaskStatus(status)
    taskDao.update(task)
  }

  /**
    *
    * @param taskId 任务标识
    * @param status 任务状态
    */
  private def persistStatusWithException(taskId: Any, status: String, taskDao: TaskDao, ex: String) = {
    val task = new Task()
    task.setTaskid(taskId.asInstanceOf[Long])
    task.setTaskStatus(status)
    task.setTaskException(ex)
    taskDao.update(task)
  }

  /**
    *
    * @param show   持久化show对象
    * @param client elasticsearch client
    */
  def addShow(show: Show, client: TransportClient): Unit = try {
    val todayTime = DateUtils.getTodayTime
    val content = XContentFactory.jsonBuilder.startObject.
      field("numvalue", show.numvalue).
      field("timeLine", show.name).
      field("category", show.category).
      field("taskid", show.taskid).
      field("filmName", show.filmName).
      field("addTime", todayTime).endObject

    client.prepareIndex(Constants.SEARCH_INDEX, Constants.SEARCH_TYPE).setSource(content).get
    println(show.filmName + " Persist to ES Success!")
  } catch {
    case e: IOException =>
      e.printStackTrace()
  }

  /**
    * 电影、电视剧、综艺交互式查询分析
    *
    * @param client    ElasticSearch连接客户端
    * @param spark     SparkSession
    * @param taskDao   任务Dao
    * @param task      任务实体类
    * @param startDate 起始时间
    * @param endDate   终止时间
    * @param category  类别
    * @param title     分析作品名称
    * @param indexName 索引名称
    * @param typeName  索引类型
    */
  private def analysisFilmSoapVariety(client: TransportClient, spark: SparkSession, taskDao: TaskDao, task: Task, startDate: String, endDate: String, category: String, title: String, indexName: String, typeName: String) = {
    if (category.equals("film") || category.equals("variety") || category.equals("soap")) {
      EsUtils.registerESTable(spark, "film", indexName, typeName)
      val startSQL = "select playNum,addTime,label from film where"
      var categorySQL = " ";
      if (category.equals("film")) {
        categorySQL = categorySQL + "category ='film' "
      } else if (category.equals("variety")) {
        categorySQL = categorySQL + " category ='variety' "
      } else {
        categorySQL = categorySQL + " category ='soap' "
      }

      val titleFilter = " and filmName = '" + title + "' "
      val limitsSQL = " order by addtime desc limit 7"
      val commonSQL = " and addTime>='" + startDate + "' and addTime<= '" + endDate + "'"

      val sql = startSQL + categorySQL + titleFilter + commonSQL + limitsSQL
      val film = spark.sql(sql)
      val filmRDD = film.rdd.collect()
      if (filmRDD.size == 0) {
        persistStatus(task.getTaskid, Constants.NODATA, taskDao)
      } else {
        var label: String = null;
        filmRDD.foreach(e => {
          label = e.getString(2)
          val show = new Show(e.getInt(0).toDouble, e.get(1).toString, "line", task.getTaskid, "")
          addShow(show, client)
        })

        // 计算占比
        if (label.contains(" ")) {
          label = label.replace(" ", ",")
        }
        val labelMap = new mutable.HashMap[String, Int]()
        val filmLabels = label.split(",")
        for (element <- filmLabels) {
          labelMap.put(element, 1)
        }

        val sqlPie = startSQL + categorySQL + commonSQL
        val filmPieRDD = spark.sql(sqlPie).rdd
        val allPieRDD = filmPieRDD.filter(e => (e.get(2) != null)).flatMap(e => {
          var label = e.getString(2)
          if (label.contains(" "))
            label = label.replace(" ", ",")
          label.split(",")
        }).map((_, 1)).reduceByKey(_ + _).collect()

        allPieRDD.foreach(e => {
          if (labelMap.contains(e._1)) {
            val show = new Show(e._2.toDouble, null, "pie", task.getTaskid, e._1.toString)
            addShow(show, client)
          }
        })
        // 更新状态 完成
        persistStatus(task.getTaskid, Constants.FINISH, taskDao)
      }
    }
  }

  /**
    * 图书交互式查询分析
    *
    * @param client    ElasticSearch连接客户端
    * @param spark     SparkSession
    * @param taskDao   任务Dao
    * @param task      任务实体类
    * @param startDate 起始时间
    * @param endDate   终止时间
    * @param category  类别
    * @param title     分析作品名称
    * @param indexName 索引名称
    * @param typeName  索引类型
    */
  private def analysisBook(client: TransportClient, spark: SparkSession, taskDao: TaskDao, task: Task, startDate: String, endDate: String, category: String, title: String, indexName: String, typeName: String) = {
    if (category.equals("book")) {
      EsUtils.registerESTable(spark, "book", indexName, typeName)
      val startSQL = "select commnetNum,addTime,categoryLable from book where"
      val titleFilter = " bookName = '" + title + "' and"
      val limitsSQL = " order by addTime desc limit 7"
      val commonSQL = " addTime>='" + startDate + "' and addTime<= '" + endDate + "'"

      val sql = startSQL + titleFilter + commonSQL + limitsSQL
      println(sql)
      val book = spark.sql(sql)
      val bookRDD = book.rdd.collect()
      if (bookRDD.size == 0) {
        persistStatus(task.getTaskid, Constants.NODATA, taskDao)
      } else {
        var label: String = null;
        bookRDD.foreach(e => {
          label = e.getString(2)
          val show = new Show(e.getLong(0).toDouble, e.get(1).toString, "line", task.getTaskid, "")
          addShow(show, client)
        })

        // 计算占比
        val labelMap = new mutable.HashMap[String, Int]()
        val filmLabels = label.split(" ")
        for (element <- filmLabels) {
          labelMap.put(element, 1)
        }

        val sqlPie = startSQL + commonSQL
        val filmPieRDD = spark.sql(sqlPie).rdd
        val allPieRDD = filmPieRDD.filter(x => (null != x.get(2))).flatMap(e => {
          val label = e.getString(2).split(" ")
          for (i <- 0 until label.length)
            yield (label(i), 1)
        }).reduceByKey(_ + _).collect()

        allPieRDD.foreach(e => {
          if (labelMap.contains(e._1)) {
            val show = new Show(e._2.toDouble, null, "pie", task.getTaskid, e._1.toString)
            addShow(show, client)
          }
        })
        // 更新状态 完成
        persistStatus(task.getTaskid, Constants.FINISH, taskDao)
      }
    }
  }

  /**
    * 网络文学交互式查询分析
    *
    * @param client    ElasticSearch连接客户端
    * @param spark     SparkSession
    * @param taskDao   任务Dao
    * @param task      任务实体类
    * @param startDate 起始时间
    * @param endDate   终止时间
    * @param category  类别
    * @param title     分析作品名称
    * @param indexName 索引名称
    * @param typeName  索引类型
    */
  private def analysisLiterature(client: TransportClient, spark: SparkSession, taskDao: TaskDao, task: Task, startDate: String, endDate: String, category: String, title: String, indexName: String, typeName: String) = {
    if (category.equals("literature")) {
      EsUtils.registerESTable(spark, "literature", indexName, typeName)
      val startSQL = "select clicknum,addtime,label from literature where"
      val titleFilter = " name = '" + title + "' and"
      val limitsSQL = " order by addtime desc limit 7"
      val commonSQL = " addtime>='" + startDate + "' and addtime<= '" + endDate + "'"

      val sql = startSQL + titleFilter + commonSQL + limitsSQL
      val literature = spark.sql(sql)
      val literatureRDD = literature.rdd.collect()
      if (literatureRDD.size == 0) {
        persistStatus(task.getTaskid, Constants.NODATA, taskDao)
      } else {
        var label: String = null;
        literatureRDD.foreach(e => {
          label = e.getString(2)
          val show = new Show(e.getLong(0).toDouble, e.get(1).toString, "line", task.getTaskid, "")
          addShow(show, client)
        })

        // 计算占比
        val labelMap = new mutable.HashMap[String, Int]()
        val filmLabels = label.split(" ")
        for (element <- filmLabels) {
          labelMap.put(element, 1)
        }

        val sqlPie = startSQL + commonSQL
        val filmPieRDD = spark.sql(sqlPie).rdd
        val allPieRDD = filmPieRDD.filter(x => (null != x.get(2))).flatMap(e => {
          val label = e.getString(2).split(" ")
          for (i <- 0 until label.length)
            yield (label(i), 1)
        }).reduceByKey(_ + _).collect()

        allPieRDD.foreach(e => {
          if (labelMap.contains(e._1)) {
            val show = new Show(e._2.toDouble, null, "pie", task.getTaskid, e._1.toString)
            addShow(show, client)
          }
        })
        // 更新状态 完成
        persistStatus(task.getTaskid, Constants.FINISH, taskDao)
      }
    }
  }


}
