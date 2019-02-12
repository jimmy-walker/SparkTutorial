# Debug

## :paste报错

注意当使用:paste复制函数时，一定要检查是否全部是空格，不能有回车键。可以利用选中查看。
利用sublime的crtl+H功能进行替换。

##Join发散问题（返回多行）
```scala
val period_search = spark.sql("select query, a.dt, (case when a.dt=b.dt then cnt else 0 end) as count from date_table a LEFT OUTER JOIN keyword_30_sum b on a.label=b.label")

val period_search2 = spark.sql("select query, dt, max(count) as cnt from (select query, a.dt, (case when a.dt=b.dt then cnt else 0 end) as count from date_table a LEFT OUTER JOIN keyword_30_sum b on a.label=b.label) group by query, dt ")
```

##查询函数一定要在官方页面查找返回对象的页面才能搜到方法
http://spark.apache.org/docs/2.0.0/api/scala/index.html#org.apache.spark.sql.Dataset
比如filter只有在上面这个页面内才行。
比如over必须在avg的返回对象Column上。

##ERROR LiveListenerBus
```linu
17/03/28 10:56:19 ERROR LiveListenerBus: Dropping SparkListenerEvent because no remaining room in event queue. This likely means one of the SparkListeners is too slow and cannot keep up with the rate at which tasks are being started by the scheduler.

17/03/28 10:56:19 WARN LiveListenerBus: Dropped 1 SparkListenerEvents since Thu Jan 01 08:00:00 CST 1970
```

当消息队列中的消息数超过其spark.scheduler.listenerbus.eventqueue.size设置的数量(如果没有设置,默认为10000)时，会将最新的消息移除，这些消息本来是通知任务运行状态的，由于你移除了，状态无法得到更新，所以会出现上面描述的现象

解决方法，启动时加入下列参数：
```linux
--conf spark.scheduler.listenerbus.eventqueue.size=100000
```

##ConsoleProgressBar
What you get is a `Console Progress Bar`, `[Stage 7:` shows the stage you are in now, and `(14174 + 5) / 62500]` is `(numCompletedTasks + numActiveTasks) / totalNumOfTasksInThisStage]`. The progress bar shows `numCompletedTasks` / `totalNumOfTasksInThisStage`.

##Spark优化
###数据倾斜（Data Skew、data tilt）：spark作业运行过程中，最消耗性能的地方就是shuffle过程。

绝大多数task执行得都非常快，但个别task执行极慢。比如，总共有1000个task，997个task都在1分钟之内执行完了，但是剩余两三个task却要一两个小时。这种情况很常见。

![](picture/data-skew.jpg)

数据倾斜的原理很简单：在进行shuffle的时候，必须将各个节点上相同的key拉取到某个节点上的一个task来进行处理，比如按照key进行聚合或join等操作。此时如果某个key对应的数据量特别大的话，就会发生数据倾斜。比如大部分key对应10条数据，但是个别key却对应了100万条数据，那么大部分task可能就只会分配到10条数据，然后1秒钟就运行完了;但是个别task可能分配到了100万数据，要运行一两个小时。因此，整个Spark作业的运行进度是由运行时间最长的那个task决定的。

数据倾斜只会发生在shuffle过程中。这里给大家罗列一些常用的并且可能会触发shuffle操作的算子：distinct、groupByKey、reduceByKey、aggregateByKey、join、cogroup、repartition等。出现数据倾斜时，可能就是你的代码中使用了这些算子中的某一个所导致的。

无论是使用yarn-client模式还是yarn-cluster模式，我们都可以在Spark Web UI上深入看一下当前这个stage各个task分配的数据量，从而进一步确定是不是task分配的数据不均匀导致了数据倾斜。

知道数据倾斜发生在哪一个stage之后，接着我们就需要根据stage划分原理，推算出来发生倾斜的那个stage对应代码中的哪一部分，这部分代码中肯定会有一个shuffle类算子。精准推算stage与代码的对应关系，需要对Spark的源码有深入的理解，这里我们可以介绍一个相对简单实用的推算方法：只要看到Spark代码中出现了一个shuffle类算子或者是Spark SQL的SQL语句中出现了会导致shuffle的语句(比如group by语句)，那么就可以判定，以那个地方为界限划分出了前后两个stage。

####优化groupby
```
[Stage 3:>                                                     (0 + 160) / 1500]17/12/04 10:47:50 WARN TaskSetManager: Lost task 49.0 in stage 3.0 (TID 3874, kg-dn-39, executor 10): FetchFailed(BlockManagerId(31, kg-dn-46, 8508, None), shuffleId=0, mapId=2, reduceId=49, message=org.apache.spark.shuffle.FetchFailedException: java.io.EOFException
```
查询网上资料，这个问题就是shuffle的read量大，但是partitions太小造成的，一种是控制输入数据，第二种是修改参数，比如这里的提高partitions数目。

#### 提高shuffle操作的并行度，如果我们必须要对数据倾斜迎难而上，那么建议优先使用这种方案，因为这是处理数据倾斜最简单的一种方案。
思路：**<u>增加shuffle read task的数量，可以让原本分配给一个task的多个key分配给多个task，从而让每个task处理比原来更少的数据。</u>**举例来说，如果原本有5个key，每个key对应10条数据，这5个key都是分配给一个task的，那么这个task就要处理50条数据。而增加了shuffle read task以后，每个task就分配到一个key，即每个task就处理10条数据，那么自然每个task的执行时间都会变短了。

J就是在spark.sql中使用了groupby，所以特别慢。
设置方法：`spark.conf.set("spark.sql.shuffle.partitions", 1000)`
好处：方便
缺点：对于极端情况无能为力：比如某个key对应100万数据，对于我们来说，每天一个关键词的搜索量可能最高就是几十万。但是设置过大会造成性能恶化，过多的碎片task会造成大量无谓的启动关闭task开销，还有可能导致某些task hang住无法执行。

####设置patitions的大小为2001
原因：Rule of thumb is around 128 MB per partition
但是：
If you're running out of memory on the shuffle, try setting spark.sql.shuffle.partitions to 2001.
Spark uses a different data structure for shuffle book-keeping when the number of partitions is greater than 2000:

##Executor heartbeat timed out
这是因为我们在调试出现的时候，整个程序运行流程就卡到那了。这时候 Driver 就无法接收到 Executor 发来的心跳信息了，从而产生这种异常。解决办法也很简单，只需要把下面两个参数加大即可：
```linux
spark.executor.heartbeatInterval
--conf spark.network.timeout=1200s（我采取了这一方法）
```
spark.executor.heartbeatInterval（默认10s） 参数要远比 spark.network.timeout（默认120s） 小。
```linux
WARN HeartbeatReceiver: Removing executor 10 with no recent heartbeats: 309431 ms exceeds timeout 240000 ms
[Stage 207:(7163 + 113) / 14150][Stage 209:(1445 + 0) / 6723][Stage 213:(476 + 0) / 2001]18/03/22 10:13:16 ERROR YarnScheduler: Lost executor 10 on kg-dn-81: Executor heartbeat timed out after 309431 ms
```

##WARN TaskSetManager: Lost task FetchFailedException: Failed to connect

shuffle分为`shuffle write`和`shuffle read`两部分。 
shuffle write的分区数由上一阶段的RDD分区数控制，shuffle read的分区数则是由Spark提供的一些参数控制。

shuffle write可以简单理解为类似于`saveAsLocalDiskFile`的操作，将计算的中间结果按某种规则临时放到各个executor所在的本地磁盘上。

shuffle read的时候数据的分区数则是由spark提供的一些参数控制。可以想到的是，如果这个参数值设置的很小，同时shuffle read的量很大，那么将会导致一个task需要处理的数据非常大。结果导致JVM crash，从而导致取shuffle数据失败，同时executor也丢失了，看到`Failed to connect to host`的错误，也就是executor lost的意思。有时候即使不会导致JVM crash也会造成长时间的gc。

```linux
[Stage 1:>(29 + 96) / 843][Stage 5:(4827 + -125) / 9888][Stage 8:(736 + 102) / 2493]18/03/21 16:38:52 WARN TaskSetManager: Lost task 7.0 in stage 11.0 (TID 34635, kg-dn-99, executor 17): FetchFailed(BlockManagerId(15, kg-dn-111, 38469, None), shuffleId=0, mapId=59, reduceId=7, message=
org.apache.spark.shuffle.FetchFailedException: Failed to connect to kg-dn-111/10.1.172.140:38469
```

### 解决办法(一开始我采取的是第三和第四项，<u>后来我发现减少并行数cores，从8至4，第五种方法</u>)

知道原因后问题就好解决了，主要从shuffle的数据量和处理shuffle数据的分区数两个角度入手。

1. 减少shuffle数据

   思考是否可以使用`map side join`或是`broadcast join`来规避shuffle的产生。

   将不必要的数据在shuffle前进行过滤，比如原始数据有20个字段，只要选取需要的字段进行处理即可，将会减少一定的shuffle数据。

2. SparkSQL和DataFrame的join,group by等操作

   通过`spark.sql.shuffle.partitions`控制分区数，默认为200，根据shuffle的量以及计算的复杂度提高这个值。

3. Rdd的join,groupBy,reduceByKey等操作

   <u>通过`spark.default.parallelism`控制shuffle read与reduce处理的分区数，默认为运行任务的core的总数（mesos细粒度模式为8个，local模式为本地的core总数），官方建议为设置成运行任务的core的2-3倍。</u>

   **<u>J似乎其实这与partitions参数都是设置partitions数目的，只是parallelism会被dataframe所忽略，所以之前我的设置根本没啥用。</u>**

   From the answer [here](https://stackoverflow.com/questions/33297689/number-reduce-tasks-spark), `spark.sql.shuffle.partitions` configures the number of partitions that are used when shuffling data for joins or aggregations.

   `spark.default.parallelism` is the default number of partitions in `RDD`s returned by transformations like `join`, `reduceByKey`, and `parallelize` when not set explicitly by the user. Note that `spark.default.parallelism` seems to only be working for raw `RDD` and is ignored when working with dataframes.

4. <u>提高executor的内存</u>

   <u>通过`spark.executor.memory`适当提高executor的memory值。</u>

5. <u>降低cores数目，避免下面单个core能力低下，长期卡住：J同时的线条是cores</u>
  ![](picture/stage-timeline-cores8.png)

##java.lang.OutOfMemoryError: GC overhead limit exceeded

This message means that for some reason the garbage collector is taking an excessive amount of time (by default 98% of all CPU time of the process) and recovers very little memory in each run (by default 2% of the heap).

This effectively means that your program stops doing any progress and is busy running only the garbage collection at all time.

To prevent your application from soaking up CPU time without getting anything done, the JVM throws this Error so that you have a chance of diagnosing the problem.
解决方法增大driver内存：`--driver-memory 4G`


##Failed to get broadcast
```linux
[Stage 7:(860 + 88) / 7339][Stage 10:(3 + 24) / 2001][Stage 11:>(0 + 0) / 2001]18/03/22 12:04:28 WARN TaskSetManager: Lost task 6.0 in stage 10.0 (TID 24987, kg-dn-109, executor 13): java.io.IOException: org.apache.spark.SparkException: Failed to get broadcast_19_piece0 of broadcast_19
```
J猜测有的broacast被remove了，但是接下来的task又会去获取这些broadcast，便会直接失败。
本来是想禁止禁止join的自动转broadcast功能，让其还是使用sort merge join，比如`--conf spark.sql.autoBroadcastJoinThreshold=-1`。
但是后来发现原来问题在于window function未序列化。
###window function未序列化导致该问题

**<u>J我只能认为在某些版本中存在window返回未序列化的问题。</u>**

####Task not serializable
首先会报`Task not serializable`错误，这是因为window function返回的column对象是非序列化的，这就意味着都是存在各个executor中的内存里，如果需要进一步进行计算时（比如map时），系统就会提示未进行序列化，**<u>实际上未进行序列化也是可以的，只是系统必须这么提示而已，因为下面就有种方法告诉系统这个对象不需要序列化</u>**。

根据网上的[建议](https://stackoverflow.com/a/37334595/8355906)，要么就是直接对类进行序列化；要么直接在窗口对象和column对象上加上@transient的标识符。

```scala
import org.apache.spark.sql.expressions.Window
val df = Seq(("foo", 1), ("bar", 2)).toDF("x", "y")
val w = Window.partitionBy("x").orderBy("y")
val lag_y = lag(col("y"), 1).over(w)
def f(x: Any) = x.toString
df.select(lag_y).map(f _).first //it will raise error：Task not serializable

@transient val w = Window.partitionBy("x").orderBy("y")
@transient val lag_y = lag(col("y"), 1).over(w)
df.select(lag_y).map(f _).first //it will succeed
```

####WARN TaskSetManager: Lost task；IOException: org.apache.spark.SparkException: Failed to get broadcast

但是上述方法在真正需要离开当前的executor进行序列化传输时，那么就会报错。这个错误比较隐晦，根本就不提及是序列化的问题。分析原因：

Scala provides a @transient annotation for fields that should not be serialized at all. If you mark a field as @transient, then the frame- work should not save the field even when the surrounding object is serialized. When the object is loaded, the field will be restored to the default value for the type of the field annotated as @transient.
**<u>猜测@transient标记表明不被序列化后，就等于保存在各个executor的内存中。而由于大量操作导致内存被占用，从而导致丢失掉了被当做broadcast的值，从而无法进行join，报错。但我认为并非是broadcast，因为每个executor中的值是不一样的，与传统的broadcast的定义不一样。所以方法是先进行整体persist驻留在各个executor中。</u>**

但是有时候如下代码仍然randomly报错，我猜测是因为`df_ref.persist()`还未全部persist完成，结果下一批的task，也就是调用df_ref计算的任务已经上线，既然没有persist完成，那就只能调用原本的量了，因此就会报错。所以最稳妥的方法就是把两个都persist下来。这样就有时间差可以保证完成了。或者干脆在scala函数中对类进行序列化。

此外，**根据[网上描述](https://stackoverflow.com/a/37378718/8355906)，如果只是一次，其实问题不大，spark的DAGScheduler会进行重启的，J既可以无视。**

Sparks `DAGScheduler` and it's lower level cluster manager implementation (Standalone, YARN or Mesos) will notice a task failed and will take care of rescheduling the said task as part of the overall stages executed.

```
DAGScheduler does three things in Spark (thorough explanations follow):
Computes an execution DAG, i.e. DAG of stages, for a job.
Determines the preferred locations to run each task on.
Handles failures due to shuffle output files being lost.
```

```scala
df_ref.persist()
df_ref.count()

df_term.persist()
df_term.count()
```

```scala
// val window_cover_song = Window.partitionBy("cover_song")
// val window_song = Window.partitionBy("song")
// val df_ref_diff = df_remark_ref_final.filter($"song" =!= $"cover_song")
//                                        .withColumn("cover_value", sum($"cover_hot").over(window_cover_song))
//                                        .withColumn("value", max($"hot").over(window_song)) //change avg to max, cause it will exists many-many relation between song and hot, some hots are so small, it will lower the sum value, eg:凉凉
//                                        .withColumn("term",concat($"cover_song", lit(" 原唱")))
//                                        .withColumn("penality", $"value"*penality)
//                                        .groupBy("term").agg(max("penality") as "result")
//                                        .select("term", "result")
//                                        .withColumn("alias", lit("")) 

// val df_ref_eql = df_remark_ref_final.filter($"song" === $"cover_song")
//                                       .withColumn("cover_value", sum($"cover_hot").over(window_cover_song))
//                                       .withColumn("value", max($"hot").over(window_song)) //change avg to max, cause it will exists many-many relation between song and hot, some hots are so small, it will lower the sum value, eg:凉凉
//                                       .withColumn("term",concat($"cover_song", lit(" 原唱")))
//                                       .withColumn("penality", when($"cover_value" > $"value", $"value"*penality as "result").otherwise($"value"*penality*penality as "result"))
//                                       .groupBy("term").agg(max("penality") as "result")
//                                       .select("term", "result")
//                                       .withColumn("alias", lit(""))

@transient val window_cover_song = Window.partitionBy("cover_song")
@transient val window_song = Window.partitionBy("song")
val df_ref_diff_temp = df_remark_ref_final.filter($"song" =!= $"cover_song")
val df_ref_eql_temp = df_remark_ref_final.filter($"song" === $"cover_song")
@transient val diff_cover_value = sum(df_ref_diff_temp("cover_hot")).over(window_cover_song)
@transient val diff_value = max(df_ref_diff_temp("hot")).over(window_song)
//change avg to max, cause it will exists many-many relation between song and hot, some hots are so small, it will lower the sum value, eg:凉凉
@transient val eql_cover_value = sum(df_ref_eql_temp("cover_hot")).over(window_cover_song)
@transient val eql_value = max(df_ref_eql_temp("hot")).over(window_song)

val df_ref_diff = df_ref_diff_temp.withColumn("cover_value", diff_cover_value)
                                  .withColumn("value", diff_value)
                                  .withColumn("term",concat($"cover_song", lit(" 原唱")))
                                  .withColumn("penality", $"value"*penality)
                                  .groupBy("term").agg(max("penality") as "result")
                                  .select("term", "result")

val df_ref_eql = df_ref_eql_temp.withColumn("cover_value", eql_cover_value)
                                .withColumn("value", eql_value)
                                .withColumn("term",concat($"cover_song", lit(" 原唱")))
                                .withColumn("penality", when($"cover_value" > $"value", $"value"*penality as "result").otherwise($"value"*penality*penality*penality as "result"))
                                .groupBy("term").agg(max("penality") as "result")
                                .select("term", "result")

val df_ref = df_ref_eql.union(df_ref_diff)
                        .groupBy("term").agg(max("result") as "result")
                        .withColumn("alias", lit(""))
                        .select("term", "alias","result")
df_ref.persist() 
df_ref.count()

//df_term.persist()
//df_term.count()
val df_final = df_term.union(df_ref)
                        .groupBy("term", "alias").agg(max("result") as "result")  
```


###spark sql join实现

#### Join基本实现流程

总体上来说，Join的基本实现流程如下图所示，Spark将参与Join的两张表抽象为流式遍历表(`streamIter`)和查找表(`buildIter`)，通常`streamIter`为大表，`buildIter`为小表，我们不用担心哪个表为`streamIter`，哪个表为`buildIter`，这个spark会根据join语句自动帮我们完成。

![](picture/spark-sql-join-basic.png)

在实际计算时，spark会基于`streamIter`来遍历，每次取出`streamIter`中的一条记录`rowA`，根据Join条件计算`keyA`，然后根据该`keyA`去`buildIter`中查找所有满足Join条件(`keyB==keyA`)的记录`rowBs`，并将`rowBs`中每条记录分别与`rowA`join得到join后的记录，最后根据过滤条件得到最终join的记录。

从上述计算过程中不难发现，对于每条来自`streamIter`的记录，都要去`buildIter`中查找匹配的记录，所以`buildIter`一定要是查找性能较优的数据结构。spark提供了三种join实现：sort merge join、broadcast join以及hash join。

##### sort merge join实现

要让两条记录能join到一起，首先需要将具有相同key的记录在同一个分区，所以通常来说，需要做一次shuffle，map阶段根据join条件确定每条记录的key，基于该key做shuffle write，将可能join到一起的记录分到同一个分区中，这样在shuffle read阶段就可以将两个表中具有相同key的记录拉到同一个分区处理。前面我们也提到，对于`buildIter`一定要是查找性能较优的数据结构，通常我们能想到hash表，但是对于一张较大的表来说，不可能将所有记录全部放到hash表中，另外也可以对`buildIter`先排序，查找时按顺序查找，查找代价也是可以接受的，我们知道，spark shuffle阶段天然就支持排序，这个是非常好实现的，下面是sort merge join示意图。

![](picture/spark-sql-sort-join.png)

在shuffle read阶段，分别对`streamIter`和`buildIter`进行merge sort，在遍历`streamIter`时，对于每条记录，都采用顺序查找的方式从`buildIter`查找对应的记录，由于两个表都是排序的，每次处理完`streamIter`的一条记录后，对于`streamIter`的下一条记录，只需从`buildIter`中上一次查找结束的位置开始查找，所以说每次在`buildIter`中查找不必重头开始，整体上来说，查找性能还是较优的。

##### broadcast join实现

为了能具有相同key的记录分到同一个分区，我们通常是做shuffle，那么如果`buildIter`是一个非常小的表，那么其实就没有必要大动干戈做shuffle了，直接将`buildIter`广播到每个计算节点，然后将`buildIter`放到hash表中，如下图所示。

![](picture/spark-sql-broadcast-join.png)

从上图可以看到，不用做shuffle，可以直接在一个map中完成，通常这种join也称之为map join。那么问题来了，什么时候会用broadcast join实现呢？这个不用我们担心，spark sql自动帮我们完成，当`buildIter`的估计大小不超过参数`spark.sql.autoBroadcastJoinThreshold`设定的值(默认10M)，那么就会自动采用broadcast join，否则采用sort merge join。

本来是采用`--conf spark.sql.autoBroadcastJoinThreshold=-1`，就是不让其自动broadcast，但后来我没有用，因为原来是由于其他原因造成的。

### persist/cache与broadcast的区别
RDDs are divided into partitions. These partitions themselves act as an immutable subset of the entire RDD. When Spark executes each stage of the graph, each partition gets sent to a worker which operates on the subset of the data. In turn, each worker can cache the data if the RDD needs to be re-iterated.

Broadcast variables are used to send some immutable state once to each worker. You use them when you want a local copy of a variable.

##其他error
ERROR LzoCodec: Failed to load/initialize native-lzo library。
J这是由于他们安装hadoop的问题。

##Spark配置
```linux
spark-shell \
--name jimmy_spark \
--master yarn \
--queue root.baseDepSarchQueue \
--deploy-mode client \
--executor-memory 20G \
--executor-cores 4 \
--num-executors 15 \
--driver-memory 4G \
--conf spark.scheduler.listenerbus.eventqueue.size=100000 \
--conf spark.network.timeout=1200s \
--conf spark.sql.autoBroadcastJoinThreshold=-1
```

## References
- [ConsoleProgressBar](https://stackoverflow.com/questions/30245180/what-do-the-numbers-on-the-progress-bar-mean-in-spark-shell)
- [美团Spark优化高级篇](https://tech.meituan.com/spark-tuning-pro.html)
- [美团Spark优化初级篇](https://tech.meituan.com/spark-tuning-basic.html)
- [Spark Shuffle FetchFailedException解决方案](http://www.jianshu.com/p/edd3ccc46980)
- [Spark排错与优化](http://blog.csdn.net/lsshlsw/article/details/49155087)
- [设置partitions](https://stackoverflow.com/questions/32349611/what-should-be-the-optimal-value-for-spark-sql-shuffle-partitions-or-how-do-we-i)
- [top-5-mistakes-to-avoid-when-writing-apache-spark-applications第38页](https://www.slideshare.net/cloudera/top-5-mistakes-to-avoid-when-writing-apache-spark-applications)
- [Executor heartbeat timed out](https://www.iteblog.com/archives/1192.html)
- [Spark Shuffle FetchFailedException解决方案](http://blog.csdn.net/lsshlsw/article/details/51213610)
- [Spark SQL 之 Join 实现](http://sharkdtu.com/posts/spark-sql-join.html)