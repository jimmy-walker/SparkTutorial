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

数据倾斜的原理很简单：在进行shuffle的时候，必须将各个节点上相同的key拉取到某个节点上的一个task来进行处理，比如按照key进行聚合或join等操作。此时如果某个key对应的数据量特别大的话，就会发生数据倾斜。比如大部分key对应10条数据，但是个别key却对应了100万条数据，那么大部分task可能就只会分配到10条数据，然后1秒钟就运行完了;但是个别task可能分配到了100万数据，要运行一两个小时。因此，整个Spark作业的运行进度是由运行时间最长的那个task决定的。

数据倾斜只会发生在shuffle过程中。这里给大家罗列一些常用的并且可能会触发shuffle操作的算子：distinct、groupByKey、reduceByKey、aggregateByKey、join、cogroup、repartition等。出现数据倾斜时，可能就是你的代码中使用了这些算子中的某一个所导致的。

无论是使用yarn-client模式还是yarn-cluster模式，我们都可以在Spark Web UI上深入看一下当前这个stage各个task分配的数据量，从而进一步确定是不是task分配的数据不均匀导致了数据倾斜。

知道数据倾斜发生在哪一个stage之后，接着我们就需要根据stage划分原理，推算出来发生倾斜的那个stage对应代码中的哪一部分，这部分代码中肯定会有一个shuffle类算子。精准推算stage与代码的对应关系，需要对Spark的源码有深入的理解，这里我们可以介绍一个相对简单实用的推算方法：只要看到Spark代码中出现了一个shuffle类算子或者是Spark SQL的SQL语句中出现了会导致shuffle的语句(比如group by语句)，那么就可以判定，以那个地方为界限划分出了前后两个stage。

####优化groupby
```
[Stage 3:>                                                     (0 + 160) / 1500]17/12/04 10:47:50 WARN TaskSetManager: Lost task 49.0 in stage 3.0 (TID 3874, kg-dn-39, executor 10): FetchFailed(BlockManagerId(31, kg-dn-46, 8508, None), shuffleId=0, mapId=2, reduceId=49, message=org.apache.spark.shuffle.FetchFailedException: java.io.EOFException
```
查询网上资料，这个问题就是shuffle的read量大，但是partitions太小造成的，一种是控制输入数据，第二种是修改参数，比如这里的提高partitions数目。

##### 提高shuffle操作的并行度，如果我们必须要对数据倾斜迎难而上，那么建议优先使用这种方案，因为这是处理数据倾斜最简单的一种方案。
思路：<u>**增加shuffle read task的数量，可以让原本分配给一个task的多个key分配给多个task，从而让每个task处理比原来更少的数据。**</u>举例来说，如果原本有5个key，每个key对应10条数据，这5个key都是分配给一个task的，那么这个task就要处理50条数据。而增加了shuffle read task以后，每个task就分配到一个key，即每个task就处理10条数据，那么自然每个task的执行时间都会变短了。

J就是在spark.sql中使用了groupby，所以特别慢。
设置方法：`spark.conf.set("spark.sql.shuffle.partitions", 1000)`
好处：方便
缺点：对于极端情况无能为力：比如某个key对应100万数据，对于我们来说，每天一个关键词的搜索量可能最高就是几十万。但是设置过大会造成性能恶化，过多的碎片task会造成大量无谓的启动关闭task开销，还有可能导致某些task hang住无法执行。

#####设置patitions的大小为2001
原因：Rule of thumb is around 128 MB per partition
但是：
If you're running out of memory on the shuffle, try setting spark.sql.shuffle.partitions to 2001.
Spark uses a different data structure for shuffle book-keeping when the number of partitions is greater than 2000:

##其他error
ERROR LzoCodec: Failed to load/initialize native-lzo library。
J这是由于他们安装hadoop的问题。

## References
- [ConsoleProgressBar](https://stackoverflow.com/questions/30245180/what-do-the-numbers-on-the-progress-bar-mean-in-spark-shell)
- [美团Spark优化高级篇](https://tech.meituan.com/spark-tuning-pro.html)
- [美团Spark优化初级篇](https://tech.meituan.com/spark-tuning-basic.html)
- [Spark Shuffle FetchFailedException解决方案](http://www.jianshu.com/p/edd3ccc46980)
- [Spark排错与优化](http://blog.csdn.net/lsshlsw/article/details/49155087)
- [设置partitions](https://stackoverflow.com/questions/32349611/what-should-be-the-optimal-value-for-spark-sql-shuffle-partitions-or-how-do-we-i)
- [top-5-mistakes-to-avoid-when-writing-apache-spark-applications第38页](https://www.slideshare.net/cloudera/top-5-mistakes-to-avoid-when-writing-apache-spark-applications)