package com.spark

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, File}
import java.util.ArrayList

import javax.imageio.ImageIO
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import com.spark.util.Rgb

import scala.util.control.Breaks._

/**
  * Created by IntelliJ IDEA.
  * User: Kekeer
  * Date: 2019/3/27
  * Time: 14:23
  */
object K_means_3_29 {
    val HADOOP_HOST: String = "hdfs://" + "localhost" + ":9000"
    val MASTER: String = "local"
    //    val MASTER: String = "spark://172.16.23.24:7077"

    val K: Int = 3
    //    var cluster:Array[Vector] = new Array[Vector](K)
    val cluster = new Array[ArrayList[Vector]](K)
    val temp_center = new ArrayList[Vector]


    def main(args: Array[String]): Unit = {

        val spark = SparkSession.builder()
            .appName("ReadImage")
            .master(MASTER)
            .getOrCreate
        //        val image_df = ImageSchema.readImages(LOCAL_HOST + "/user/test/image.jpg")

        val image_df = spark.read.format("image").load(HADOOP_HOST + "/user/test/image.jpg")
        val height: Int = image_df.select("image.height").first().get(0).toString.toInt
        val width: Int = image_df.select("image.width").first().get(0).toString.toInt
        val data: Array[Byte] = image_df.select("image.data").first().getAs[Array[Byte]](0)

        val lab_vec: Array[Vector] = getLabVec(width, height, getLabArr(width, height, data))
        val image_rdd = spark.sparkContext.parallelize(lab_vec)

        val old_k_center = new Array[Vector](K)
        val new_k_center = image_rdd.takeSample(true, K)


        for (iter <- 0 until K) {
            cluster(iter) = new ArrayList[Vector]
        }

        var iter: Int = 1
        val max_iter: Int = 20
        breakable(
            while (iter < max_iter) {
                for (iter <- cluster.indices) {
                    cluster(iter).clear()
                }
                temp_center.clear()
                // print center index
                println("the " + iter + " times: ")
                new_k_center.foreach(println)

                val closest = image_rdd.map(iter => (closestCenter(iter, new_k_center), iter)).collect()

                cluster.foreach(center => updateCenter(center))
                for (i <- 0 until K) {
                    new_k_center(i) = temp_center.get(i)
                }

                iter = iter + 1
            }
        )

        spark.stop()

    }

    def updateCenter(center: ArrayList[Vector]): Unit = {
        val sum: Array[Double] = new Array[Double](2)
        for (i <- 0 until center.size()) {
            val now = center.get(i).toArray
            for (j <- 0 until 2) {
                sum(j) = sum(j) + now(j)
            }
        }
        for (j <- 0 until 2) {
            sum(j) = sum(j) / center.size
        }
        temp_center.add(Vectors.dense(sum))
    }

    def closestCenter(point: Vector, centers: Array[Vector]): Int = {
        var bestIndex = 0
        var closest = Double.PositiveInfinity
        for (i <- centers.indices) {
            val tempDist = Math.sqrt(Vectors.sqdist(point, centers(i))) //欧氏距离
            if (tempDist < closest) {
                closest = tempDist
                bestIndex = i
            }
        }
        cluster(bestIndex).add(point)
        bestIndex
    }

    def getLabVec(width: Int, height: Int, lab_arr: Array[Array[Double]]): Array[Vector] = {
        val lab_vec: Array[Vector] = new Array[Vector](height * width)
        var iter: Int = 0
        for (h <- 0 until height) {
            for (w <- 0 until width) {
                lab_vec(width * h + w) = Vectors.dense(lab_arr(width * h + w)(1), lab_arr(width * h + w)(2))
                iter = iter + 3
            }
        }
        lab_vec
    }

    def getLabArr(width: Int, height: Int, data: Array[Byte]): Array[Array[Double]] = {
        val lab_arr: Array[Array[Double]] = new Array[Array[Double]](height * width)
        var iter: Int = 0
        for (h <- 0 until height) {
            for (w <- 0 until width) {
                lab_arr(width * h + w) = Rgb.Rgb2Lab(byteToInt(data(iter + 2)), byteToInt(data(iter + 1)), byteToInt(data(iter)))
                iter = iter + 3
            }
        }
        lab_arr
    }

    // 将字节数组rgb写入文件
    def writeImageByByte(width: Int, height: Int, data: Array[Byte]): Unit = {
        val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var iter: Int = 0
        for (h <- 0 until height) {
            for (w <- 0 until width) {
                image.setRGB(w, h, getRgb(data(iter + 2), data(iter + 1), data(iter)))
                iter = iter + 3
            }
        }

        ImageIO.write(image, "jpg", new File("output.jpg"))
    }


    def writeImageByLabDouble(width: Int, height: Int, lab_arr: Array[Array[Double]]): Unit = {

        val rgb_arr: Array[Array[Double]] = new Array[Array[Double]](height * width)
        for (h <- 0 until height) {
            for (w <- 0 until width) {
                rgb_arr(width * h + w) = Rgb.Lab2Rgb(lab_arr(h * width + w))
            }
        }

        val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (h <- 0 until height) {
            for (w <- 0 until width) {
                image.setRGB(w, h, getRgbByDouble(rgb_arr(h * width + w)(0), rgb_arr(h * width + w)(1), rgb_arr(h * width + w)(2)))
            }
        }

        ImageIO.write(image, "jpg", new File("output.jpg"))
    }

    def byteToInt(data: Byte): Int = {
        data & 0xff
    }

    // 得到rgb值
    def getRgb(r: Byte, g: Byte, b: Byte): Int = {
        new Color(byteToInt(r), byteToInt(g), byteToInt(b)).getRGB
    }

    def getRgbByDouble(r: Double, g: Double, b: Double): Int = {
        new Color(r.toInt, g.toInt, b.toInt).getRGB
    }
}
