package dev.oaktech.bv.viewmodel.ugc

import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.biliapi.repositories.UgcRepository
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class UgcEmotionViewModel(
    override val ugcRepository: UgcRepository
) : UgcViewModel(
    ugcRepository = ugcRepository,
    ugcType = UgcTypeV2.Emotion
)