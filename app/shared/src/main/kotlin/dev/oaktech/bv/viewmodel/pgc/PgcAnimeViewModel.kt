package dev.oaktech.bv.viewmodel.pgc

import dev.oaktech.biliapi.entity.pgc.PgcType
import dev.oaktech.biliapi.repositories.PgcRepository
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class PgcAnimeViewModel(
    pgcRepository: PgcRepository
) : PgcViewModel(
    pgcRepository = pgcRepository,
    pgcType = PgcType.Anime
)