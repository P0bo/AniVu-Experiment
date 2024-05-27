package com.skyd.anivu.model.repository.importexport

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import be.ceau.opml.OpmlParser
import be.ceau.opml.OpmlWriter
import be.ceau.opml.entity.Body
import be.ceau.opml.entity.Head
import be.ceau.opml.entity.Opml
import be.ceau.opml.entity.Outline
import com.skyd.anivu.appContext
import com.skyd.anivu.base.BaseRepository
import com.skyd.anivu.ext.getAppName
import com.skyd.anivu.ext.toAbsoluteDateTimeString
import com.skyd.anivu.ext.validateFileName
import com.skyd.anivu.model.bean.FeedBean
import com.skyd.anivu.model.bean.GroupBean
import com.skyd.anivu.model.bean.GroupWithFeedBean
import com.skyd.anivu.model.db.dao.FeedDao
import com.skyd.anivu.model.db.dao.GroupDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.util.Date
import javax.inject.Inject
import kotlin.random.Random
import kotlin.time.measureTime

class ImportExportRepository @Inject constructor(
    private val feedDao: FeedDao,
    private val groupDao: GroupDao,
) : BaseRepository() {
    private suspend fun requestGroupWithFeedsList(): Flow<List<GroupWithFeedBean>> {
        return combine(
            groupDao.getGroupWithFeeds(),
            groupDao.getGroupIds(),
        ) { groupList, groupIds ->
            groupList to feedDao.getFeedsNotIn(groupIds)
        }.map { (groupList, defaultFeeds) ->
            mutableListOf<GroupWithFeedBean>().apply {
                add(GroupWithFeedBean(GroupBean.DefaultGroup, defaultFeeds))
                addAll(groupList)
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun importOpmlMeasureTime(
        opmlUri: Uri,
        strategy: ImportOpmlConflictStrategy,
    ): Flow<ImportOpmlResult> {
        return flow {
            var importedFeedCount = 0
            val time = measureTime {
                appContext.contentResolver.openInputStream(opmlUri)!!.use {
                    parseOpml(it)
                }.forEach { opmlGroupWithFeed ->
                    importedFeedCount += strategy.handle(
                        groupDao = groupDao,
                        feedDao = feedDao,
                        opmlGroupWithFeed = opmlGroupWithFeed,
                    )
                }
            }.inWholeMilliseconds

            emit(
                ImportOpmlResult(
                    time = time,
                    importedFeedCount = importedFeedCount,
                )
            )
        }.flowOn(Dispatchers.IO)
    }

    data class ImportOpmlResult(
        val time: Long,
        val importedFeedCount: Int,
    )

    private fun parseOpml(inputStream: InputStream): List<OpmlGroupWithFeed> {
        fun MutableList<OpmlGroupWithFeed>.addGroup(group: GroupBean) = add(
            OpmlGroupWithFeed(group = group, feeds = mutableListOf())
        )

        fun MutableList<OpmlGroupWithFeed>.addFeed(feed: FeedBean) = last().feeds.add(feed)
        fun MutableList<OpmlGroupWithFeed>.addFeedToDefault(feed: FeedBean) =
            first().feeds.add(feed)


        val opml = OpmlParser().parse(inputStream)
        val groupWithFeedList = mutableListOf<OpmlGroupWithFeed>().apply {
            addGroup(GroupBean.DefaultGroup)
        }

        opml.body.outlines.forEach {
            // Only feeds
            if (it.subElements.isEmpty()) {
                // It's a empty group
                if (it.attributes["xmlUrl"] == null) {
                    if (!it.attributes["isDefault"].toBoolean()) {
                        groupWithFeedList.addGroup(
                            GroupBean(
                                groupId = "",
                                name = it.attributes["title"] ?: it.text.toString(),
                            )
                        )
                    }
                } else {
                    groupWithFeedList.addFeedToDefault(
                        FeedBean(
                            url = it.attributes["xmlUrl"]!!,
                            title = it.attributes["title"] ?: it.text.toString(),
                            description = it.attributes["description"],
                            link = it.attributes["link"],
                            icon = it.attributes["icon"],
                            groupId = GroupBean.DefaultGroup.groupId,
                            nickname = it.attributes["nickname"],
                        )
                    )
                }
            } else {
                if (!it.attributes["isDefault"].toBoolean()) {
                    groupWithFeedList.addGroup(
                        GroupBean(
                            groupId = "",
                            name = it.attributes["title"] ?: it.text.toString(),
                        )
                    )
                }
                it.subElements.forEach { outline ->
                    groupWithFeedList.addFeed(
                        FeedBean(
                            url = outline.attributes["xmlUrl"]!!,
                            title = outline.attributes["title"] ?: outline.text!!,
                            description = outline.attributes["description"],
                            link = outline.attributes["link"],
                            icon = outline.attributes["icon"],
                            groupId = "",
                            nickname = outline.attributes["nickname"],
                        )
                    )
                }
            }
        }

        return groupWithFeedList
    }

    data class OpmlGroupWithFeed(
        val group: GroupBean,
        val feeds: MutableList<FeedBean>,
    )

    suspend fun exportOpmlMeasureTime(outputDir: Uri): Flow<Long> {
        return flow {
            emit(measureTime { exportOpml(outputDir) }.inWholeMilliseconds)
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun exportOpml(outputDir: Uri) {
        val text = OpmlWriter().write(
            Opml(
                "2.0",
                Head(
                    appContext.getAppName(),
                    Date().toString(), null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                ),
                Body(requestGroupWithFeedsList().first().map { groupWithFeeds ->
                    Outline(
                        mutableMapOf(
                            "text" to groupWithFeeds.group.name,
                            "title" to groupWithFeeds.group.name,
                            "isDefault" to (groupWithFeeds.group.groupId == GroupBean.DefaultGroup.groupId).toString(),
                        ),
                        groupWithFeeds.feeds.map { feedView ->
                            val feed = feedView.feed
                            Outline(
                                mutableMapOf(
                                    "text" to feed.title,
                                    "title" to feed.title,
                                    "xmlUrl" to feed.url,
                                    "htmlUrl" to feed.url,
                                ).apply {
                                    feed.description?.let { put("description", it) }
                                    feed.link?.let { put("link", it) }
                                    feed.icon?.let { put("icon", it) }
                                    feed.nickname?.let { put("nickname", it) }
                                },
                                listOf()
                            )
                        }
                    )
                })
            )
        )!!

        saveOpml(text, outputDir)
    }

    private fun saveOpml(text: String, outputDir: Uri) {
        val appName = appContext.getAppName()
        val fileName = (appName + "_" + System.currentTimeMillis().toAbsoluteDateTimeString() +
                "_" + Random.nextInt(0, Int.MAX_VALUE).toString() + ".opml").validateFileName()

        val documentFile = DocumentFile.fromTreeUri(appContext, outputDir)!!
        val opmlUri: Uri = documentFile.createFile("text/x-opml", fileName)!!.uri
        val opmlOutputStream = appContext.contentResolver.openOutputStream(opmlUri)!!
        opmlOutputStream.writer().use { writer ->
            writer.write(text)
        }
    }
}