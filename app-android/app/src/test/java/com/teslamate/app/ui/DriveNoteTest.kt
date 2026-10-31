package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.102.0（第 10 轮 CDO Polish）：驾驶平顺分 ⓘ 的权重与长度。**
 *
 * ## 这一层盯住什么
 *
 * ① **权重**：服务端 `api/handlers.go` 的 `drivingScore()` 里五项封顶是
 *    急刹 30 + 急加 20 + 速度 20 + 能耗 15 + 夜驾 15 ⇒
 *    平滑相关 **70** 分、能耗与夜驾 **30** 分。
 *    ⇒ App 的 ⓘ 与 Hermes 的口径字符串**都必须等于这两个数**
 *    （`scripts/client-gates.sh` 闸门 14 负责跨语言的那一半）。
 *
 * ② **长度**：ⓘ 必须 ≤ [NOTE_WIDTH_LIMIT]（160 ≈ 展开后 3 行）。
 *    这条 ⓘ 是 provider 形态（`noteDriveScore()`）⇒ **静态闸门看不见**
 *    ⇒ 只能靠运行时单测钉住。
 *
 * ③ **不重复**：副标题已经写着「按 ≥20 公里的 **20 趟**算，共 1583 公里」⇒
 *    ⓘ 里不该再出现门槛与趟数。
 *    ⚠️ 但「更短的行程不在里面」**必须留着** —— 它是 v0.50.0 特意加的：
 *    让用户知道**自己那些短途被门槛筛掉了**（本车 72.8% 的趟不到 20 公里）。
 */
class DriveNoteTest {

    /** 实测本机：avg_score 63.6 → 64；20 趟 / 1583 公里 ⇒ 每趟 79 公里。 */
    @Test
    fun `驾驶平顺分口径在上限内`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = 79)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    /** 权重必须与服务端公式一致：70 / 30。 */
    @Test
    fun `权重是服务端的七三开`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = 79)
        assertTrue("必须写 70 分：$note", note.contains("70 分"))
        assertTrue("必须写 30 分：$note", note.contains("30 分"))
        assertFalse("不能还写 60 分：$note", note.contains("60 分"))
        assertFalse("不能还写 40 分：$note", note.contains("40 分"))
    }

    /** 副标题已经写了门槛与趟数 ⇒ ⓘ 里不重复。 */
    @Test
    fun `不重复副标题里的门槛与趟数`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = 79)
        assertFalse("不能重复门槛：$note", note.contains("门槛"))
        assertFalse("不能重复门槛公里数：$note", note.contains("20 公里"))
        assertFalse("不能重复趟数：$note", note.contains("20 趟"))
    }

    /** ⚠️ v0.50.0 的意图必须保住：告诉用户短途被筛掉了。 */
    @Test
    fun `保留门槛筛掉了什么`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = 79)
        assertTrue("必须说短途不在里面：$note", note.contains("更短的行程不在里面"))
        assertTrue("必须给出每趟平均：$note", note.contains("79 公里"))
    }

    /** v0.74.0：均值必须**明说它是均值**，免得和主角的中位数混。 */
    @Test
    fun `明说均值`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = 79)
        assertTrue("必须明说平均：$note", note.contains("平均"))
        assertTrue("必须给出均值：$note", note.contains("64 分"))
        assertTrue("必须点明卡上是中位数：$note", note.contains("中位数"))
    }

    /** 没有平均公里数时，那半句不能出现（否则会显示一个编不出来的数）。 */
    @Test
    fun `没有平均公里数时不提它`() {
        val note = noteDriveScore(avgScore = 64, avgKmPerTrip = null)
        assertFalse("不能出现公里：$note", note.contains("公里"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }
}
