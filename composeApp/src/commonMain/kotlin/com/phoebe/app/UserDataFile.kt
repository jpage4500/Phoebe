package com.phoebe.app

/** The files the Backup & restore settings save and load. */
enum class UserDataFile(
    val suggestedFileName: String,
    val label: String,
) {
    Backup("phoebe-backup.json", "Phoebe backup"),
    FavoritePlaylists("phoebe-favorite-playlists.json", "favorite playlists"),
    RadioStations("phoebe-radio-stations.json", "radio stations"),
}
