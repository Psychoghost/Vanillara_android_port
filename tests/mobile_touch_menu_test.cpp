#include "mobile_touch_menu.h"

#include <assert.h>
#include <string.h>

static void test_layout_in_bounds_top_right(void)
{
	MobileMenu m;
	MobileMenu_Init(&m);
	int sizes[][2] = {{1920, 1080}, {2560, 1600}, {1280, 720}, {640, 480}, {1080, 1920}};
	for (unsigned s = 0; s < sizeof(sizes) / sizeof(sizes[0]); ++s) {
		MobileMenu_Layout(&m, sizes[s][0], sizes[s][1]);
		assert(m.size > 0);
		for (int i = 0; i < MOBILE_MENU_COUNT; ++i) {
			MobileMenuRect r = MobileMenu_ItemRect(&m, i);
			assert(r.x >= sizes[s][0] / 2);
			assert(r.x + r.w <= sizes[s][0]);
			assert(r.y >= 0 && r.y + r.h <= sizes[s][1]);
			if (i > 0) {
				MobileMenuRect p = MobileMenu_ItemRect(&m, i - 1);
				assert(r.y >= p.y + p.h);
			}
		}
	}
}

static void test_hit_test(void)
{
	MobileMenu m;
	MobileMenu_Init(&m);
	MobileMenu_Layout(&m, 1920, 1080);
	for (int i = 0; i < MOBILE_MENU_COUNT; ++i) {
		MobileMenuRect r = MobileMenu_ItemRect(&m, i);
		assert(MobileMenu_HitTest(&m, (float)(r.x + r.w / 2), (float)(r.y + r.h / 2)) == i);
	}
	assert(MobileMenu_HitTest(&m, 10.0f, 10.0f) == -1);
	assert(MobileMenu_HitTest(&m, 960.0f, 540.0f) == -1);
}

static void test_finger_ownership(void)
{
	MobileMenu m;
	MobileMenu_Init(&m);
	MobileMenu_Layout(&m, 1920, 1080);
	MobileMenuRect r = MobileMenu_ItemRect(&m, MOBILE_MENU_ESC);
	float cx = (float)(r.x + r.w / 2);
	float cy = (float)(r.y + r.h / 2);

	assert(MobileMenu_Down(&m, 7, 10.0f, 10.0f) == -1);
	assert(!MobileMenu_Owns(&m, 7));
	assert(MobileMenu_Down(&m, 7, cx, cy) == MOBILE_MENU_ESC);
	assert(MobileMenu_Owns(&m, 7));
	assert(m.pressed_item == MOBILE_MENU_ESC);
	assert(MobileMenu_Down(&m, 8, cx, cy) == -1);
	MobileMenu_Up(&m, 8);
	assert(MobileMenu_Owns(&m, 7));
	MobileMenu_Up(&m, 7);
	assert(!MobileMenu_Owns(&m, 7));
	assert(m.pressed_item == -1);
}

static void test_activate_toggles_and_actions(void)
{
	MobileTouchButtonState b;
	MobileTouchButtons_Init(&b);

	assert(MobileMenu_Activate(&b, MOBILE_MENU_ATK) == MOBILE_MENU_ACTION_NONE);
	assert(b.force_attack_active && MobileMenu_IsActive(&b, MOBILE_MENU_ATK));
	MobileMenu_Activate(&b, MOBILE_MENU_ATK);
	assert(!b.force_attack_active);

	MobileMenu_Activate(&b, MOBILE_MENU_MOV);
	assert(b.force_move_active);
	MobileMenu_Activate(&b, MOBILE_MENU_RMB);
	assert(b.right_click_mode);
	assert(MobileTouchButtons_GetEffectiveVK(&b, VK_LBUTTON) == VK_RBUTTON);

	assert(MobileMenu_Activate(&b, MOBILE_MENU_ESC) == MOBILE_MENU_ACTION_KEY_ESC);
	assert(MobileMenu_Activate(&b, MOBILE_MENU_SCT) == MOBILE_MENU_ACTION_KEY_SCATTER);
	assert(MobileMenu_Activate(&b, MOBILE_MENU_GFX) == MOBILE_MENU_ACTION_CYCLE_SCALE);
	assert(!MobileMenu_IsActive(&b, MOBILE_MENU_GFX));
	assert(MobileMenu_Activate(&b, 99) == MOBILE_MENU_ACTION_NONE);
}

static void test_apply_modifiers(void)
{
	unsigned char keys[256];
	MobileTouchButtonState b;
	memset(keys, 0, sizeof(keys));
	MobileTouchButtons_Init(&b);

	MobileMenu_ApplyModifiers(&b, keys);
	assert(!keys[VK_CONTROL] && !keys[VK_MENU]);

	b.force_attack_active = 1;
	MobileMenu_ApplyModifiers(&b, keys);
	assert(keys[VK_CONTROL] && !keys[VK_MENU]);

	b.force_move_active = 1;
	MobileMenu_ApplyModifiers(&b, keys);
	assert(keys[VK_CONTROL] && keys[VK_MENU]);
}

static void test_scale_quality_cycle(void)
{
	assert(strcmp(MobileMenu_NextScaleQuality("nearest"), "linear") == 0);
	assert(strcmp(MobileMenu_NextScaleQuality("linear"), "best") == 0);
	assert(strcmp(MobileMenu_NextScaleQuality("best"), "nearest") == 0);
	assert(strcmp(MobileMenu_NextScaleQuality(0), "nearest") == 0);
	assert(strcmp(MobileMenu_NextScaleQuality("junk"), "nearest") == 0);
}

static void test_labels_have_glyphs(void)
{
	for (int i = 0; i < MOBILE_MENU_COUNT; ++i) {
		char const *label = MobileMenu_Label(i);
		assert(strlen(label) == 3);
		for (int c = 0; label[c]; ++c) {
			int lit = 0;
			for (int row = 0; row < 7; ++row) {
				unsigned char bits = MobileMenu_GlyphRow(label[c], row);
				assert(bits < 32);
				lit |= bits;
			}
			assert(lit != 0);
		}
	}
	assert(MobileMenu_GlyphRow('?', 0) == 0);
	assert(MobileMenu_GlyphRow('A', 7) == 0);
}

int main(void)
{
	test_layout_in_bounds_top_right();
	test_hit_test();
	test_finger_ownership();
	test_activate_toggles_and_actions();
	test_apply_modifiers();
	test_scale_quality_cycle();
	test_labels_have_glyphs();
	return 0;
}
