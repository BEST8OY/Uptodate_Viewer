package com.clinref.app.di

import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.StreamingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideSafetyValidator(): SafetyValidator = SafetyValidator()

    @Provides
    @Singleton
    fun provideStreamingManager(): StreamingManager = StreamingManager()

    @Provides
    @Singleton
    fun provideReliabilityManager(): ReliabilityManager = ReliabilityManager()

    @Provides
    @Singleton
    fun provideKoogAgentFactory(
        koogAgentFactory: KoogAgentFactory
    ): KoogAgentFactory = koogAgentFactory
}
