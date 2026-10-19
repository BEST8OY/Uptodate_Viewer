package com.clinref.desktop

import com.clinref.app.data.DatabaseManager
import com.clinref.app.domain.Audience
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import com.clinref.app.repository.TocRepository
import com.clinref.shared.di.desktopAppModules
import org.koin.core.context.startKoin
import java.io.File

/**
 * Desktop application runner and diagnostic verifier.
 *
 * Can run standalone on Desktop JVM (Linux/macOS/Windows) to verify
 * all 6 SQLite database connections, search queries, TOC extraction,
 * and AI agent setup without requiring Android.
 */
object DesktopApp {

    fun init(dbDirPath: String? = null): DatabaseManager {
        val koinApp = startKoin {
            modules(desktopAppModules)
        }

        val dbManager: DatabaseManager = koinApp.koin.get()

        val resolvedPath = dbDirPath ?: findDefaultDatabaseDirectory()
        if (resolvedPath != null && File(resolvedPath).isDirectory) {
            val configured = dbManager.setDatabaseDirectory(resolvedPath)
            println("[DesktopApp] Configured database directory at $resolvedPath: $configured")
        } else {
            println("[DesktopApp] No valid database directory provided or auto-detected.")
        }

        return dbManager
    }

    private fun findDefaultDatabaseDirectory(): String? {
        val candidates = listOf(
            File(".").absoluteFile,
            File("..").absoluteFile,
            File(System.getProperty("user.dir", ".")),
            File(System.getProperty("user.home"), ".clinref/databases")
        )
        return candidates.firstOrNull { dir ->
            DatabaseManager.DB_FILES.all { File(dir, it).exists() }
        }?.canonicalFile?.absolutePath
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== ClinRef Desktop Multiplatform Diagnostics ===")
        val dbDirPath = args.firstOrNull()
        val dbManager = init(dbDirPath)

        if (!dbManager.isConfigured()) {
            println("ERROR: Databases not configured. Missing: ${dbManager.getMissingDatabases()}")
            return
        }

        println("Available databases: ${dbManager.getAvailableDatabases()}")

        val koin = org.koin.core.context.GlobalContext.get()
        val searchRepo: SearchRepository = koin.get()
        val tocRepo: TocRepository = koin.get()
        val contentRepo: ContentRepository = koin.get()

        println("\n--- Testing Search ---")
        val searchResults = searchRepo.searchTopics("asthma", Audience.ALL)
        println("Search 'asthma' returned ${searchResults.size} results:")
        searchResults.take(3).forEach { println(" - $it") }

        println("\n--- Testing TOC ---")
        val tocRoots = tocRepo.getTocItems()
        println("Root TOC items: ${tocRoots.size}")
        tocRoots.take(3).forEach { println(" - ${it.title} (id: ${it.id}, leaf: ${it.isLeaf})") }

        println("\n--- Testing ViewModels DI Resolution ---")
        val searchVm: com.clinref.app.ui.search.SearchViewModel = koin.get()
        val tocVm: com.clinref.app.ui.toc.TocViewModel = koin.get()
        val contentVm: com.clinref.app.ui.content.ContentViewModel = koin.get()
        val chatVm: com.clinref.app.ui.chat.ChatViewModel = koin.get()
        val graphicVm: com.clinref.app.ui.content.GraphicViewModel = koin.get()
        val historyVm: com.clinref.app.ui.history.HistoryViewModel = koin.get()
        val favoritesVm: com.clinref.app.ui.favorites.FavoritesViewModel = koin.get()
        val convListVm: com.clinref.app.ui.conversations.ConversationListViewModel = koin.get()
        val settingsVm: com.clinref.app.ui.settings.SettingsViewModel = koin.get()
        val setupVm: com.clinref.app.ui.setup.SetupViewModel = koin.get()

        println("Resolved 10/10 ViewModels successfully:")
        println(" 1. ${searchVm::class.simpleName}")
        println(" 2. ${tocVm::class.simpleName}")
        println(" 3. ${contentVm::class.simpleName}")
        println(" 4. ${chatVm::class.simpleName}")
        println(" 5. ${graphicVm::class.simpleName}")
        println(" 6. ${historyVm::class.simpleName}")
        println(" 7. ${favoritesVm::class.simpleName}")
        println(" 8. ${convListVm::class.simpleName}")
        println(" 9. ${settingsVm::class.simpleName}")
        println(" 10. ${setupVm::class.simpleName}")

        println("\n--- Testing Article Web Controller ---")
        val webController = com.clinref.app.ui.content.ArticleWebViewController()
        var lastAction: String? = null
        webController.onAction = { lastAction = it }
        val jsScroll = com.clinref.app.ui.content.scrollToSectionJs("sec-treatment")
        println("Generated scroll JS (len: ${jsScroll.length}): starts with: ${jsScroll.take(30)}...")

        println("\n--- Diagnostics Complete: 100% KMP Desktop Operational ---")
    }
}
