# Function

承接上一页关于Spark中的数据类型，这页主要讲一些主要函数操作。**这里假设我们使用dataframe进行操作，则需要查询相关的dataset的function。**

## orderBy和sort

**对于Spark来说两者是相同的！**

### 排序

```scala
df_final.filter(col("searchterm").startsWith(keyword_test)).sort("result".desc).show()
```

### 抽样

```scala
import org.apache.spark.sql.functions.rand
df_split.orderBy(rand()).limit(20).show()
df_split.sort(rand()).limit(20).show()
//rand Generate a random column with independent and identically distributed (i.i.d.) samples from U[0.0, 1.0].
```

## Select

### 返回子集

Selects a set of columns. 返回dataframe

```scala
// The following two are equivalent:
df.select("colA", "colB")
df.select($"colA", $"colB") //当无需对内容进行比较或运算时，可以省略掉$，$就代表dataframe的名字。可以看做有了$就会是一列了。
```

### 返回统计值

```scala
val df_variable = df_cal_save.select(mean(df_cal_save("hot")),stddev_samp(df_cal_save("hot")),mean(df_cal_save("burst")),stddev_samp(df_cal_save("burst")))
val (hot_mean, hot_std, burst_mean, burst_std) = (df_variable.first.getDouble(0), df_variable.first.getDouble(1), df_variable.first.getDouble(2), df_variable.first.getDouble(3))
```

##agg聚合函数

###返回统计值

```scala
// df.agg(...) is a shorthand for ds.groupBy().agg(...)
df.agg(max($"age"), avg($"salary"))
df.groupBy().agg(max($"age"), avg($"salary"))
```
## distinct

Returns a new Dataset that contains only the unique rows from this Dataset. This is an alias for `dropDuplicates`. 

## collect

Returns an array that contains all of [Row](https://spark.apache.org/docs/2.1.0/api/scala/org/apache/spark/sql/Row.html)s in this Dataset.

Running collect requires moving all the data into the application's driver process, and doing so on a very large dataset can crash the driver process with OutOfMemoryError.

## Reference

- [dataset function](https://spark.apache.org/docs/2.1.0/api/scala/index.html#org.apache.spark.sql.Dataset)