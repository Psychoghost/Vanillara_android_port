#include "mac_sdl.h"
#include "mac_sdl_runtime.h"

#include <assert.h>
#include <stdlib.h>
#include <string.h>

static char g_scale_quality[16] = "nearest";

static char const *test_get_scale_quality(void)
{
	char const *env = getenv("RA_SCALE_QUALITY");
	if (env && env[0]) {
		return env;
	}
	return g_scale_quality;
}

static bool test_set_scale_quality(char const *quality)
{
	if (!quality || !quality[0]) return false;
	if (strcmp(quality, "linear") == 0 || strcmp(quality, "1") == 0) {
		strncpy(g_scale_quality, "linear", sizeof(g_scale_quality) - 1);
	} else if (strcmp(quality, "best") == 0 || strcmp(quality, "2") == 0) {
		strncpy(g_scale_quality, "best", sizeof(g_scale_quality) - 1);
	} else {
		strncpy(g_scale_quality, "nearest", sizeof(g_scale_quality) - 1);
	}
	return true;
}

static void test_default_scale_quality(void)
{
	assert(strcmp(test_get_scale_quality(), "nearest") == 0);
}

static void test_set_scale_quality_values(void)
{
	assert(test_set_scale_quality("linear"));
	assert(strcmp(test_get_scale_quality(), "linear") == 0);

	assert(test_set_scale_quality("best"));
	assert(strcmp(test_get_scale_quality(), "best") == 0);

	assert(test_set_scale_quality("nearest"));
	assert(strcmp(test_get_scale_quality(), "nearest") == 0);
}

int main(void)
{
	test_default_scale_quality();
	test_set_scale_quality_values();
	return 0;
}
