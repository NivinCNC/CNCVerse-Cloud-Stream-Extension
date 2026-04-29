package com.horis.cncverse

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class NetflixMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "ta"
    override var mainUrl = "https://net52.cc"
    override var name = "Netflix"
    override val hasMainPage = true

    private val ott = "nf"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val apiBase = resolveApiUrl()

        val response = app.get(
            "$apiBase/newtv/main.php",
            headers = buildNewTvHeaders(ott, mapOf("Page" to "all", "Recentplay" to "", "Watchlist" to "", "Usertoken" to ""))
        ).parsed<NewTvMainResponse>()

        val imgReferer = response.img_referer ?: apiBase
        val items = response.post.orEmpty().map { category ->
            val ids = category.ids?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val useHorizontal = category.row == "h"
            val template = (if (useHorizontal) response.imgcdn_h else response.imgcdn_v) ?: response.imgcdn_v ?: response.imgcdn_h
            val results = ids.mapNotNull { id ->
                newAnimeSearchResponse("", NewTvId(id).toJson()) {
                    posterUrl = buildPosterUrl(template, id)
                    posterHeaders = mapOf("Referer" to imgReferer)
                }
            }
            HomePageList(category.cate.orEmpty(), results, isHorizontalImages = useHorizontal)
        }

        return newHomePageResponse(items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiBase = resolveApiUrl()
        val data = app.get(
            "$apiBase/newtv/search.php?s=${URLEncoder.encode(query, "UTF-8")}",
            headers = buildNewTvHeaders(ott)
        ).parsed<NewTvSearchResponse>()

        val imgReferer = data.img_referer ?: apiBase
        val template = data.detailsimgcdn ?: data.imgcdn

        return data.searchResult.orEmpty().map { item ->
            newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                posterUrl = buildPosterUrl(template, item.id)
                posterHeaders = mapOf("Referer" to imgReferer)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiBase = resolveApiUrl()
        val id = parseJson<NewTvId>(url).id

        val data = app.get(
            "$apiBase/newtv/post.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to ""))
        ).parsed<NewTvPostResponse>()

        val title = data.title ?: id
        val playbackId = data.main_id ?: id
        val cast = data.cast?.split(",")?.map { it.trim() }?.map { ActorData(Actor(it)) } ?: emptyList()
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime ?: "")
        val isSeries = data.type == "t" || data.episodes?.any { it != null } == true
        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = buildPosterUrl(data.morelike_poster, it.id)
                posterHeaders = mapOf("Referer" to apiBase)
            }
        }

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, NewTvLoadData(title, playbackId).toJson()) {
                posterUrl = buildPosterUrl(data.main_poster, id)
                backgroundPosterUrl = buildPosterUrl(data.main_poster, id)
                posterHeaders = mapOf("Referer" to apiBase)
                plot = data.desc; year = data.year?.toIntOrNull(); tags = genre
                actors = cast; this.score = Score.from10(rating); duration = runTime
                recommendations = suggest
            }
        }

        val episodes = arrayListOf<Episode>()

        if (data.episodes.isNullOrEmpty()) {
            if (data.type != "t") episodes.add(newEpisode(NewTvLoadData(title, playbackId)) { name = title })
        } else {
            val selectedSeasonIdx = data.season?.indexOfFirst { it.selected == true }?.takeIf { it >= 0 }
            val selectedSeasonId = selectedSeasonIdx?.let { data.season?.getOrNull(it)?.id } ?: data.nextPageSeason
            val selectedSeasonNumber = selectedSeasonIdx?.plus(1)

            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id.orEmpty())) {
                    this.name = it.t
                    episode = it.ep?.toIntOrNull() ?: it.epNum?.replace("E", "").orEmpty().toIntOrNull()
                    season = selectedSeasonNumber ?: it.sNum?.replace("S", "").orEmpty().toIntOrNull()
                    posterUrl = buildPosterUrl(data.ep_poster, it.id.orEmpty())
                    this.runTime = it.timeVal?.replace("m", "").orEmpty().toIntOrNull()
                    description = it.ep_desc
                }
            }

            if (data.nextPageShow == 1 && !selectedSeasonId.isNullOrBlank())
                episodes.addAll(getEpisodes(title, selectedSeasonId, 2, data.ep_poster, selectedSeasonNumber))

            data.season?.forEachIndexed { index, season ->
                if (season.id != selectedSeasonId && !season.id.isNullOrBlank())
                    episodes.addAll(getEpisodes(title, season.id, 1, data.ep_poster, index + 1))
            }
        }

        if (data.type == "t" && episodes.isEmpty() && !data.season.isNullOrEmpty()) {
            data.season.forEachIndexed { index, season ->
                if (!season.id.isNullOrBlank())
                    episodes.addAll(getEpisodes(title, season.id, 1, data.ep_poster, index + 1))
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = buildPosterUrl(data.main_poster, id)
            backgroundPosterUrl = buildPosterUrl(data.main_poster, id)
            posterHeaders = mapOf("Referer" to apiBase)
            plot = data.desc; year = data.year?.toIntOrNull(); tags = genre
            actors = cast; this.score = Score.from10(rating); duration = runTime
            recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, sid: String, page: Int,
        epPoster: String? = null, seasonNumber: Int? = null
    ): List<Episode> {
        val apiBase = resolveApiUrl()
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = app.get(
                "$apiBase/newtv/episodes.php",
                params = mapOf("id" to sid, "page" to pg.toString()),
                headers = buildNewTvHeaders(ott)
            ).parsed<NewTvEpisodesResponse>()

            data.episodes.orEmpty().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id.orEmpty())) {
                    name = it.t
                    episode = it.ep?.toIntOrNull() ?: it.epNum?.replace("E", "").orEmpty().toIntOrNull()
                    season = seasonNumber ?: it.sNum?.replace("S", "").orEmpty().toIntOrNull()
                    posterUrl = buildPosterUrl(epPoster, it.id.orEmpty())
                    this.runTime = it.timeVal?.replace("m", "").orEmpty().toIntOrNull()
                    description = it.ep_desc
                }
            }

            if (data.nextPageShow != 1) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = resolveApiUrl()
        val id = parseJson<NewTvLoadData>(data).id

        val response = app.get(
            "$apiBase/newtv/player.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false

        callback.invoke(newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
            this.referer = response.referer ?: apiBase
        })
        return true
    }
}