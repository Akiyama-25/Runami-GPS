package io.github.jqssun.gpssetter.module

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.module.util.ApplicationScope
import io.github.jqssun.gpssetter.room.AppDatabase
import io.github.jqssun.gpssetter.room.FavoriteDao
import io.github.jqssun.gpssetter.room.LogDao
import io.github.jqssun.gpssetter.update.GitHubService
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun createGitHubService(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/repos/Akiyama-25/Runami-GPS/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideDownloadManger(application: Application) =
        application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Singleton
    @Provides
    fun provideGithubService(retrofit: Retrofit): GitHubService =
        retrofit.create(GitHubService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(application: Application, callback: AppDatabase.Callback): AppDatabase =
        Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .addCallback(callback)
            .build()

    @Singleton
    @Provides
    fun providesUserDao(favoriteDatabase: AppDatabase): FavoriteDao =
        favoriteDatabase.favoriteDao()

    @Singleton
    @Provides
    fun providesLogDao(appDatabase: AppDatabase): LogDao =
        appDatabase.logDao()

    @Singleton
    @Provides
    fun provideSettingRepo(): PrefManager =
        PrefManager

    @ApplicationScope
    @Provides
    @Singleton
    fun providesApplicationScope() = CoroutineScope(SupervisorJob())
}
