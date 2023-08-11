/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
class RepositorySearch(private val repo: AudioItemRepository) {

    suspend fun searchTracks(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracks = repo.getDatabase()?.trackDAO()?.search(sanitizeSearchQuery(query))
        tracks?.forEach {
            audioItems += repo.createAudioItemForTrack(it)
        }
        return audioItems
    }

    suspend fun searchAlbums(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = repo.getDatabase()?.albumDAO()?.search(sanitizeSearchQuery(query))
        albums?.forEach {
            audioItems += repo.createAudioItemForAlbum(it)
        }
        return audioItems
    }

    suspend fun searchTracksForAlbum(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = repo.getDatabase()?.albumDAO()?.search(sanitizeSearchQuery(query))
        albums?.forEach {
            audioItems += repo.getTracksForAlbum(it.albumId)
        }
        return audioItems
    }

    suspend fun searchAlbumAndCompilationArtists(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = repo.getDatabase()?.artistDAO()?.searchAlbumAndCompilationArtists(sanitizeSearchQuery(query))
        artists?.forEach {
            audioItems += repo.createAudioItemForArtist(it)
        }
        return audioItems
    }

    suspend fun searchTracksForArtist(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = repo.getDatabase()?.artistDAO()?.search(sanitizeSearchQuery(query))
        artists?.forEach {
            audioItems += repo.getTracksForArtist(it.artistId)
        }
        return audioItems
    }

    suspend fun searchTrackByArtist(track: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByArtist = repo.getDatabase()?.trackDAO()?.searchWithArtist(
            sanitizeSearchQuery(track), sanitizeSearchQuery(artist)
        )
        tracksByArtist?.forEach {
            audioItems += repo.createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchTrackByAlbum(track: String, album: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByAlbum = repo.getDatabase()?.trackDAO()?.searchWithAlbum(
            sanitizeSearchQuery(track), sanitizeSearchQuery(album)
        )
        tracksByAlbum?.forEach {
            audioItems += repo.createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchAlbumByArtist(album: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albumsByArtist = repo.getDatabase()?.albumDAO()?.searchWithArtist(
            sanitizeSearchQuery(album), sanitizeSearchQuery(artist)
        )
        albumsByArtist?.forEach {
            audioItems += repo.createAudioItemForAlbum(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchFiles(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val files = repo.getDatabase()?.pathDAO()?.search(sanitizeSearchQuery(query))
        files?.forEach {
            val uri = Util.createURIForPath(repo.storageID, it.absolutePath)
            audioItems += if (it.isDirectory) {
                AudioItemLibrary.createAudioItemForDirectory(Directory(uri))
            } else {
                AudioItemLibrary.createAudioItemForFile(AudioFile(uri))
            }
        }
        return audioItems
    }

    private fun sanitizeSearchQuery(query: String): String {
        val queryEscaped = query.replace(Regex.fromLiteral("\""), "\"\"")
        return "\"*$queryEscaped*\""
    }

}
