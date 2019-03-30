package com.spark.util

import java.awt.Color
import java.awt.color.ColorSpace
import java.io.ByteArrayInputStream

import com.spark.ReadImage
import javax.imageio.ImageIO
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

/**
  * Created by IntelliJ IDEA.
  * User: Kekeer
  * Date: 2019/3/27
  * Time: 17:21
  */
object ImageSchema{

    val undefinedImageType = "Undefined"

    val ocvTypes = Map(
        undefinedImageType -> -1,
        "CV_8U" -> 0, "CV_8UC1" -> 0, "CV_8UC2" -> 8, "CV_8UC3" -> 16, "CV_8UC4" -> 24,
        "CV_8S" -> 1, "CV_8SC1" -> 1, "CV_8SC2" -> 9, "CV_8SC3" -> 17, "CV_8SC4" -> 25,
        "CV_16U"-> 2, "CV_16UC1"-> 2, "CV_16UC2"->10, "CV_16UC3"-> 18, "CV_16UC4"-> 26,
        "CV_16S"-> 3, "CV_16SC1"-> 3, "CV_16SC2"->11, "CV_16SC3"-> 19, "CV_16SC4"-> 27,
        "CV_32S"-> 4, "CV_32SC1"-> 4, "CV_32SC2"->12, "CV_32SC3"-> 20, "CV_32SC4"-> 28,
        "CV_32F"-> 5, "CV_32FC1"-> 5, "CV_32FC2"->13, "CV_32FC3"-> 21, "CV_32FC4"-> 29,
        "CV_64F"-> 6, "CV_64FC1"-> 6, "CV_64FC2"->14, "CV_64FC3"-> 22, "CV_64FC4"-> 30
    )

    /** Schema for the image column: Row(String, Int, Int, Int, Array[Byte]) */
    val columnSchema = StructType(
        StructField("origin", StringType,  true) ::
            StructField("height", IntegerType, false) ::
            StructField("width",  IntegerType, false) ::
            StructField("nChannels",  IntegerType, false) ::
            StructField("mode", StringType, false) ::        //OpenCV-compatible type: CV_8UC3 in most cases
            StructField("data",  BinaryType, false) :: Nil)   //bytes in OpenCV-compatible order: row-wise BGR in most cases

    //dataframe with a single column of images named "image" (nullable)
    private val imageDFSchema = StructType(StructField("image", columnSchema, true) :: Nil)

    def getOrigin(row: Row): String = row.getString(0)
    def getHeight(row: Row): Int = row.getInt(1)
    def getWidth(row: Row): Int = row.getInt(2)
    def getNChannels(row: Row): Int = row.getInt(3)
    def getMode(row: Row): String = row.getString(4)
    def getData(row: Row): Array[Byte] = row.getAs[Array[Byte]](5)

    /** Check if the dataframe column contains images (i.e. has ImageSchema)
      *
      * @param df       Dataframe
      * @param column   Column name
      * @return         True if the given column matches the image schema
      */
    def isImageColumn(df: DataFrame, column: String): Boolean =
        df.schema(column).dataType == columnSchema

    /** Default values for the invalid image
      *
      * @param origin Origin of the invalid image
      * @return       Row with the default values
      */
    private def invalidImageRow(origin: String): Row = Row(Row(origin, -1, -1, -1, undefinedImageType, Array.ofDim[Byte](0)))

    /** Convert the compressed image (jpeg, png, etc.) into OpenCV representation and store it in dataframe Row
      *
      * @param origin   Arbitrary string that identifies the image
      * @param bytes    Image bytes (for example, jpeg)
      * @return         Dataframe Row or None (if the decompression fails)
      */
    private def decode(origin: String, bytes: Array[Byte]): Option[Row] = {

        val img = ImageIO.read(new ByteArrayInputStream(bytes))

        if (img == null) {
            None
        } else {

            val is_gray = img.getColorModel.getColorSpace.getType == ColorSpace.TYPE_GRAY
            val has_alpha = img.getColorModel.hasAlpha

            val height = img.getHeight
            val width = img.getWidth
            val (nChannels, mode) = if(is_gray)        (1, "CV_8UC1")
            else if(has_alpha) (4, "CV_8UC4")
            else               (3, "CV_8UC3")

            assert(height*width*nChannels < 1e9, "image is too large")
            val decoded = Array.ofDim[Byte](height*width*nChannels)

            // grayscale images in Java require special handling to get the correct intensity
            if(is_gray){
                var offset = 0
                val raster = img.getRaster
                for(h <- 0 until height) {
                    for (w <- 0 until width) {
                        decoded(offset) = raster.getSample(w, h, 0).toByte
                        offset += 1
                    }
                }
            }
            else{
                var offset = 0
                for (h <- 0 until height) {
                    for (w <- 0 until width) {
                        val color = new Color(img.getRGB(w, h))

                        if(w == 0 && h==0) {
                            println(color.getBlue+" "+color.getBlue.toByte)
                            println(color.getGreen+" "+color.getGreen.toByte)
                            println(color.getRed+" "+color.getRed.toByte)
                        }

                        decoded(offset) = color.getBlue.toByte
                        decoded(offset + 1) = color.getGreen.toByte
                        decoded(offset + 2) = color.getRed.toByte
                        if (nChannels == 4) {
                            decoded(offset + 3) = color.getAlpha.toByte
                        }
                        offset += nChannels
                    }
                }
            }

            // the internal "Row" is needed, because the image is a single dataframe column
            Some(Row(Row(origin, height, width, nChannels, mode, decoded)))
        }
    }

    /** Read the directory of images from the local or remote source
      *
      * @param path               Path to the image directory
      * @param sparkSession       Spark Session
      * @param recursive          Recursive path search flag
      * @param numPartitions      Number of the dataframe partitions
      * @param dropImageFailures  Drop the files that are not valid images from the result
      * @param sampleRatio        Fraction of the files loaded
      * @return                   Dataframe with a single column "image" of images; see ImageSchema for the details
      */
    def readImages(path: String,
                   sparkSession: SparkSession = null,  // do not use Option; it complicates Python call
                   recursive: Boolean = false,
                   numPartitions: Int = 0,
                   dropImageFailures: Boolean = false,
                   sampleRatio: Double = 1.0): DataFrame =   {
        require(sampleRatio <= 1.0 && sampleRatio >= 0, "sampleRatio should be between 0 and 1")

        val session = if (sparkSession != null) sparkSession else SparkSession.builder().getOrCreate
        val partitions = if (numPartitions > 0) numPartitions else session.sparkContext.defaultParallelism

        val oldRecursiveFlag = RecursiveFlag.setRecursiveFlag(Some(recursive.toString), session)
        val oldPathFilter: Option[Class[_]] =
            if (sampleRatio < 1)
                SamplePathFilter.setPathFilter(Some(classOf[SamplePathFilter]), Some(sampleRatio), session)
            else
                None

        var result: DataFrame = null
        try {
            val streams = session.sparkContext.binaryFiles(path, partitions)
                .repartition(partitions)

            val images = if(dropImageFailures){
                streams.flatMap{
                    case (origin, stream) => decode(origin, stream.toArray)
                }
            }
            else {
                streams.map{
                    case (origin, stream) => decode(origin, stream.toArray).getOrElse(invalidImageRow(origin))
                }
            }

            result = session.createDataFrame(images, imageDFSchema)
        }
        finally {
            // return Hadoop flags to the original values
            RecursiveFlag.setRecursiveFlag(oldRecursiveFlag, session)
            SamplePathFilter.setPathFilter(oldPathFilter, None, session)
            ()
        }

        result
    }
}
