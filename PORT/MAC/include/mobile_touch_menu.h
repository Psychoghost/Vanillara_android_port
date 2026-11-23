#ifndef MOBILE_TOUCH_MENU_H
#define MOBILE_TOUCH_MENU_H

#include <string.h>

#include "mobile_touch_buttons.h"

/*
 * On-screen translucent shortcut menu, drawn in the top-right corner.
 * Pure layout / hit-test / state logic. No SDL calls, so it is unit-testable.
 * The runtime draws the rects and glyphs and performs the returned action.
 */

enum MobileMenuItem {
	MOBILE_MENU_ATK = 0, /* Force Attack (Ctrl) */
	MOBILE_MENU_MOV,     /* Force Move (Alt) */
	MOBILE_MENU_RMB,     /* Right-click mode */
	MOBILE_MENU_ESC,     /* Escape key */
	MOBILE_MENU_SCT,     /* 'X' scatter */
	MOBILE_MENU_GFX,     /* Cycle render scale quality */
	MOBILE_MENU_COUNT
};

enum MobileMenuAction {
	MOBILE_MENU_ACTION_NONE = 0,
	MOBILE_MENU_ACTION_KEY_ESC,
	MOBILE_MENU_ACTION_KEY_SCATTER,
	MOBILE_MENU_ACTION_CYCLE_SCALE
};

struct MobileMenuRect {
	int x;
	int y;
	int w;
	int h;
};

struct MobileMenu {
	int size;
	int margin;
	int gap;
	int window_w;
	int window_h;
	int finger_active;
	long long finger;
	int pressed_item; /* -1 when no finger holds a button */
};

static inline void MobileMenu_Init(MobileMenu *m)
{
	if (!m) return;
	memset(m, 0, sizeof(*m));
	m->pressed_item = -1;
}

/* window_w / window_h are window pixels. Size scales with the shorter side, like the D-pad. */
static inline void MobileMenu_Layout(MobileMenu *m, int window_w, int window_h)
{
	if (!m) return;
	int shorter = window_w < window_h ? window_w : window_h;
	m->window_w = window_w;
	m->window_h = window_h;
	m->size = shorter / 9;
	m->margin = shorter / 40;
	m->gap = m->margin / 2;
}

static inline MobileMenuRect MobileMenu_ItemRect(MobileMenu const *m, int item)
{
	MobileMenuRect r = {0, 0, 0, 0};
	if (!m || item < 0 || item >= MOBILE_MENU_COUNT) return r;
	r.w = m->size;
	r.h = m->size;
	r.x = m->window_w - m->margin - m->size;
	r.y = m->margin + item * (m->size + m->gap);
	return r;
}

/* Returns item index under the point, or -1. */
static inline int MobileMenu_HitTest(MobileMenu const *m, float x, float y)
{
	if (!m || m->size <= 0) return -1;
	for (int i = 0; i < MOBILE_MENU_COUNT; ++i) {
		MobileMenuRect r = MobileMenu_ItemRect(m, i);
		if (x >= (float)r.x && x < (float)(r.x + r.w) && y >= (float)r.y && y < (float)(r.y + r.h)) {
			return i;
		}
	}
	return -1;
}

static inline int MobileMenu_Owns(MobileMenu const *m, long long finger)
{
	return m && m->finger_active && m->finger == finger;
}

/* Claims the finger when it lands on a button. Returns the item, or -1 if the touch missed the menu. */
static inline int MobileMenu_Down(MobileMenu *m, long long finger, float x, float y)
{
	if (!m || m->finger_active) return -1;
	int item = MobileMenu_HitTest(m, x, y);
	if (item < 0) return -1;
	m->finger_active = 1;
	m->finger = finger;
	m->pressed_item = item;
	return item;
}

static inline void MobileMenu_Up(MobileMenu *m, long long finger)
{
	if (!MobileMenu_Owns(m, finger)) return;
	m->finger_active = 0;
	m->pressed_item = -1;
}

static inline char const *MobileMenu_Label(int item)
{
	switch (item) {
		case MOBILE_MENU_ATK: return "ATK";
		case MOBILE_MENU_MOV: return "MOV";
		case MOBILE_MENU_RMB: return "RMB";
		case MOBILE_MENU_ESC: return "ESC";
		case MOBILE_MENU_SCT: return "SCT";
		case MOBILE_MENU_GFX: return "GFX";
		default: return "";
	}
}

/* Applies a button press. Toggles update state directly; other items return an action for the runtime. */
static inline MobileMenuAction MobileMenu_Activate(MobileTouchButtonState *buttons, int item)
{
	switch (item) {
		case MOBILE_MENU_ATK: MobileTouchButtons_ToggleForceAttack(buttons); return MOBILE_MENU_ACTION_NONE;
		case MOBILE_MENU_MOV: MobileTouchButtons_ToggleForceMove(buttons); return MOBILE_MENU_ACTION_NONE;
		case MOBILE_MENU_RMB: MobileTouchButtons_ToggleRightClickMode(buttons); return MOBILE_MENU_ACTION_NONE;
		case MOBILE_MENU_ESC: return MOBILE_MENU_ACTION_KEY_ESC;
		case MOBILE_MENU_SCT: return MOBILE_MENU_ACTION_KEY_SCATTER;
		case MOBILE_MENU_GFX: return MOBILE_MENU_ACTION_CYCLE_SCALE;
		default: return MOBILE_MENU_ACTION_NONE;
	}
}

static inline int MobileMenu_IsActive(MobileTouchButtonState const *buttons, int item)
{
	if (!buttons) return 0;
	switch (item) {
		case MOBILE_MENU_ATK: return buttons->force_attack_active;
		case MOBILE_MENU_MOV: return buttons->force_move_active;
		case MOBILE_MENU_RMB: return buttons->right_click_mode;
		default: return 0;
	}
}

/*
 * Real keyboard state is re-read on key events, so OR the on-screen modifiers in
 * after that refresh. key_state is the runtime's 256-entry MacKeyState array.
 */
static inline void MobileMenu_ApplyModifiers(MobileTouchButtonState const *buttons, unsigned char *key_state)
{
	if (!buttons || !key_state) return;
	if (buttons->force_attack_active) key_state[VK_CONTROL & 0xFF] = 1;
	if (buttons->force_move_active) key_state[VK_MENU & 0xFF] = 1;
}

/* nearest -> linear -> best -> nearest. Returns a static string. */
static inline char const *MobileMenu_NextScaleQuality(char const *current)
{
	if (current && strcmp(current, "nearest") == 0) return "linear";
	if (current && strcmp(current, "linear") == 0) return "best";
	return "nearest";
}

/*
 * 5x7 bitmap glyphs for the labels. Row 0 is the top row, bit 4 is the leftmost pixel.
 * Covers every letter used by MobileMenu_Label(). Unknown characters return 0.
 */
static inline unsigned char MobileMenu_GlyphRow(char c, int row)
{
	static const struct { char ch; unsigned char rows[7]; } glyphs[] = {
		{'A', {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
		{'B', {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}},
		{'C', {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}},
		{'E', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}},
		{'F', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}},
		{'G', {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}},
		{'K', {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}},
		{'M', {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}},
		{'O', {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}},
		{'R', {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}},
		{'S', {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}},
		{'T', {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}},
		{'V', {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}},
		{'X', {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}}
	};
	if (row < 0 || row >= 7) return 0;
	for (unsigned i = 0; i < sizeof(glyphs) / sizeof(glyphs[0]); ++i) {
		if (glyphs[i].ch == c) return glyphs[i].rows[row];
	}
	return 0;
}

#endif
