#include <SDL.h>
#include <SDL_system.h>

#include <limits.h>
#include <string>
#include <string.h>
#include <sys/stat.h>
#include <stdio.h>
#include <unistd.h>

#include <windows.h>

int PASCAL WinMain(HINSTANCE instance, HINSTANCE previous, char *command_line, int command_show);

static bool path_exists(char const *path)
{
    struct stat info;
    return path && stat(path, &info) == 0;
}

static bool try_resource_root(char const *base)
{
    static char const *config_path = "assets/redalert/allies/INSTALL/REDALERT.INI";
    if (!base || !base[0]) {
        return false;
    }

    std::string resource_root = std::string(base) + "/redalert-root";
    if (chdir(resource_root.c_str()) != 0) {
        return false;
    }
    return path_exists(config_path);
}

static bool try_absolute_root(char const *path)
{
    static char const *config_path = "assets/redalert/allies/INSTALL/REDALERT.INI";
    if (!path || !path[0]) {
        return false;
    }
    if (chdir(path) != 0) {
        return false;
    }
    return path_exists(config_path);
}

/*
** The Java setup screen records the active redalert-root here. That lets the
** app use visible internal storage (/sdcard/Vanillara) when the user grants
** file access, and private internal storage otherwise, without the engine
** ever guessing or falling back to a removable SD card.
*/
static bool try_pointer_file(void)
{
    char const *internal = SDL_AndroidGetInternalStoragePath();
    if (!internal || !internal[0]) {
        return false;
    }

    std::string pointer = std::string(internal) + "/vanillara-root.txt";
    FILE *file = fopen(pointer.c_str(), "rb");
    if (!file) {
        return false;
    }

    char buffer[PATH_MAX];
    size_t length = fread(buffer, 1, sizeof(buffer) - 1, file);
    fclose(file);
    buffer[length] = 0;
    while (length > 0
        && (buffer[length - 1] == '\n' || buffer[length - 1] == '\r'
            || buffer[length - 1] == ' ')) {
        buffer[--length] = 0;
    }
    return try_absolute_root(buffer);
}

static bool select_resource_root(void)
{
    static char const *config_path = "assets/redalert/allies/INSTALL/REDALERT.INI";
    if (path_exists(config_path)) {
        return true;
    }

    // 1. The root chosen by the setup screen (internal shared storage, or the
    //    app-private internal fallback). Never a removable SD card.
    if (try_pointer_file()) {
        return true;
    }

    // 2. App-private internal storage.
    if (try_resource_root(SDL_AndroidGetInternalStoragePath())) {
        return true;
    }

    // 3. Legacy builds used app-specific external storage. Keep reading it so
    //    an existing installation still launches.
    if (try_resource_root(SDL_AndroidGetExternalStoragePath())) {
        return true;
    }

    return false;
}

extern "C" int SDL_main(int argc, char **argv)
{
    if (!select_resource_root()) {
        SDL_Log("Vanillara: no imported Red Alert data found in app storage.");
        return 1;
    }

    std::string command_line;
    for (int index = 1; index < argc; ++index) {
        if (!command_line.empty()) {
            command_line += ' ';
        }
        command_line += argv[index];
    }

    int result = WinMain((HINSTANCE)0, (HINSTANCE)0, command_line.empty() ? (char *)"" : (char *)command_line.c_str(), SW_RESTORE);

    /*
    **	The 1996 codebase assumes the process dies when the game exits: it keeps
    **	global state (_Mouse, Screen, the DirectDraw surfaces, the mixfile heaps)
    **	that is never torn down or re-initialised. Android, however, keeps the
    **	process alive after the activity finishes, so launching again re-enters
    **	SDL_main() against those stale globals and segfaults immediately --
    **	Block_Mouse() dereferences _Mouse->Screen, which points at freed memory.
    **
    **	Terminate the process instead so the next launch starts clean. _exit()
    **	rather than exit() because the static destructors belong to that same
    **	un-resettable global state and are not safe to run here; flush first so
    **	anything the game buffered on the way out still lands.
    */
    fflush(NULL);
    _exit(result);
}
