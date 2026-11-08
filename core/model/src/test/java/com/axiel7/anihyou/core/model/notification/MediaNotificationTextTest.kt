package com.axiel7.anihyou.core.model.notification

import android.content.res.Resources
import com.axiel7.anihyou.core.network.NotificationsQuery
import com.axiel7.anihyou.core.model.notification.GenericNotification.Companion.toGenericNotifications
import com.axiel7.anihyou.core.model.notification.GenericNotification.Companion.localizedText
import com.axiel7.anihyou.core.network.type.NotificationType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaNotificationTextTest {
    @Test fun onlyStructuredTitleChanges() {
        val media = MediaNotificationText("Same", suffix = " context mentions Same")
        assertEquals("中文 context mentions Same", media.render("中文"))
        assertEquals("Same context mentions Same", media.render())
        assertEquals(" context mentions Same", media.copy(title = null).render())
    }

    @Test fun episodeUsesStructuredValueEvenWithNonEnglishContext() {
        val notification = GenericNotification(
            id = 1, text = "第 12 集 中文 已播出", imageUrl = null, contentId = 1,
            type = NotificationType.AIRING, createdAt = null,
            mediaText = MediaNotificationText("原名", "第 12 集 ", " 已播出", 12),
        )
        assertEquals(12, notification.numEpisode())
        assertEquals("原名", notification.mediaTitle())
        assertEquals("第 12 集 译名 已播出", notification.mediaText?.render("译名"))
    }

    @Test fun nonAiringHasNoEpisode() {
        val notification = GenericNotification(
            id = 1, text = "Episode 7", imageUrl = null, contentId = 0,
            type = NotificationType.MEDIA_DELETION, createdAt = null,
        )
        assertNull(notification.numEpisode())
        assertNull(notification.mediaTitle())
    }
    @Test fun apiConversionPreservesAiringAndDeletionFields() {
        val airing = NotificationsQuery.OnAiringNotification(
            id = 10, contexts = listOf("Episode ", " of Same ", " aired"), animeId = 42,
            episode = 3, media = NotificationsQuery.Media("Media", NotificationsQuery.Title("MediaTitle", "Same"), null, false, 42),
            type = NotificationType.AIRING, createdAt = 100,
        )
        val deletion = NotificationsQuery.OnMediaDeletionNotification(
            id = 11, context = " deleted", reason = null, deletedMediaTitle = "Same",
            type = NotificationType.MEDIA_DELETION, createdAt = 101,
        )
        val converted = listOf(apiNotification(airing = airing), apiNotification(deletion = deletion)).toGenericNotifications()
        assertEquals("Episode 3 of Same Same aired", converted[0].text)
        assertEquals(42, converted[0].contentId)
        assertEquals(3, converted[0].numEpisode())
        assertEquals("Episode 3 of Same 中文 aired", converted[0].mediaText?.render("中文"))
        assertEquals("Same deleted", converted[1].text)
        assertEquals(0, converted[1].contentId)
        assertNull(converted[1].mediaText)
    }

    @Test fun mediaConversionRetainsContextAndReason() {
        val related = NotificationsQuery.OnRelatedMediaAdditionNotification(1, " mentions Same", 7,
            NotificationsQuery.Media1("Media", NotificationsQuery.Title1("Title", "Same"), null, false, 7), NotificationType.RELATED_MEDIA_ADDITION, null)
        val changed = NotificationsQuery.OnMediaDataChangeNotification(2, " changed", 7, "Same reason",
            NotificationsQuery.Media2("Media", NotificationsQuery.Title2("Title", "Same"), null, false, 7), NotificationType.MEDIA_DATA_CHANGE, null)
        val merged = NotificationsQuery.OnMediaMergeNotification(3, " merged Same", null, 7,
            NotificationsQuery.Media3("Media", NotificationsQuery.Title3("Title", "Same"), null, false, 7), NotificationType.MEDIA_MERGE, null)
        val result = listOf(apiNotification(related = related), apiNotification(changed = changed), apiNotification(merge = merged)).toGenericNotifications()
        assertEquals("中文 mentions Same", result[0].mediaText?.render("中文"))
        assertEquals("中文 changed\nSame reason", result[1].mediaText?.render("中文"))
        assertEquals("中文 merged Same", result[2].mediaText?.render("中文"))
        assertEquals(listOf(7, 7, 7), result.map { it.contentId })
    }

    @Test fun localizedMediaBranchesUseOnlyStructuredTitleSlot() {
        val resources = mockk<Resources>()
        every { resources.getString(any(), "中文") } returns "localized:中文"

        val related = notification(
            type = NotificationType.RELATED_MEDIA_ADDITION,
            rawTitle = null,
            suffix = " was recently added to the site.",
        )
        assertEquals("localized:中文", related.localizedText(resources, displayTitle = "中文"))

        val changed = notification(
            type = NotificationType.MEDIA_DATA_CHANGE,
            rawTitle = null,
            suffix = " received site data changes\nreason mentions Same",
        )
        assertEquals("localized:中文\nreason mentions Same", changed.localizedText(resources, displayTitle = "中文"))

        val sameNameContext = notification(
            type = NotificationType.RELATED_MEDIA_ADDITION,
            rawTitle = "Same",
            suffix = " mentions Same",
        )
        assertEquals("中文 mentions Same", sameNameContext.localizedText(resources, displayTitle = "中文"))
    }

    @Test fun missingTitleAndIdNeverRenderLiteralNull() {
        val resources = mockk<Resources>()
        val related = notification(
            type = NotificationType.RELATED_MEDIA_ADDITION,
            rawTitle = null,
            suffix = " was recently added to the site.",
            contentId = 0,
        )

        val result = related.localizedText(resources, displayTitle = null)

        assertEquals(" was recently added to the site.", result)
        assertEquals(false, result?.contains("null"))
    }

    @Test fun apiConversionWithMissingTitleDoesNotCreateLiteralNull() {
        val related = NotificationsQuery.OnRelatedMediaAdditionNotification(
            id = 1,
            context = " was recently added to the site.",
            mediaId = 7,
            media = NotificationsQuery.Media1(
                "Media", NotificationsQuery.Title1("Title", null), null, false, 7,
            ),
            type = NotificationType.RELATED_MEDIA_ADDITION,
            createdAt = null,
        )

        val result = listOf(apiNotification(related = related)).toGenericNotifications().single()

        assertEquals(" was recently added to the site.", result.text)
        assertNull(result.mediaText?.title)
    }

    private fun notification(
        type: NotificationType,
        rawTitle: String?,
        suffix: String,
        contentId: Int = 7,
    ) = GenericNotification(
        id = 1,
        text = rawTitle.orEmpty() + suffix,
        imageUrl = null,
        contentId = contentId,
        type = type,
        createdAt = null,
        mediaText = MediaNotificationText(rawTitle, suffix = suffix),
    )

    private fun apiNotification(
        airing: NotificationsQuery.OnAiringNotification? = null,
        deletion: NotificationsQuery.OnMediaDeletionNotification? = null,
        related: NotificationsQuery.OnRelatedMediaAdditionNotification? = null,
        changed: NotificationsQuery.OnMediaDataChangeNotification? = null,
        merge: NotificationsQuery.OnMediaMergeNotification? = null,
    ) = NotificationsQuery.Notification(
        __typename = "Notification", onAiringNotification = airing, onFollowingNotification = null,
        onActivityMessageNotification = null, onActivityMentionNotification = null,
        onActivityReplyNotification = null, onActivityReplySubscribedNotification = null,
        onActivityLikeNotification = null, onActivityReplyLikeNotification = null,
        onThreadCommentMentionNotification = null, onThreadCommentReplyNotification = null,
        onThreadCommentSubscribedNotification = null, onThreadCommentLikeNotification = null,
        onThreadLikeNotification = null, onRelatedMediaAdditionNotification = related,
        onMediaDataChangeNotification = changed, onMediaMergeNotification = merge,
        onMediaDeletionNotification = deletion, onMediaSubmissionUpdateNotification = null,
        onStaffSubmissionUpdateNotification = null, onCharacterSubmissionUpdateNotification = null,
    )

}
