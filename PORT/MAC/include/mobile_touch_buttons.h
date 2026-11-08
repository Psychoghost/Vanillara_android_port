#ifndef MOBILE_TOUCH_BUTTONS_H
#define MOBILE_TOUCH_BUTTONS_H

#include "windows.h"

enum MobileTouchButtonType {
	MOBILE_BUTTON_NONE = 0,
	MOBILE_BUTTON_FORCE_ATTACK, // Ctrl modifier
	MOBILE_BUTTON_FORCE_MOVE,   // Alt modifier
	MOBILE_BUTTON_RIGHT_CLICK,  // Right Click Mode
	MOBILE_BUTTON_ESC,          // Escape key
	MOBILE_BUTTON_SCATTER       // 'X' key scatter command
};

struct MobileTouchButtonState {
	int force_attack_active;
	int force_move_active;
	int right_click_mode;
};

static inline void MobileTouchButtons_Init(MobileTouchButtonState *buttons)
{
	if (!buttons) {
		return;
	}
	buttons->force_attack_active = 0;
	buttons->force_move_active = 0;
	buttons->right_click_mode = 0;
}

static inline void MobileTouchButtons_ToggleForceAttack(MobileTouchButtonState *buttons)
{
	if (buttons) {
		buttons->force_attack_active = !buttons->force_attack_active;
	}
}

static inline void MobileTouchButtons_ToggleForceMove(MobileTouchButtonState *buttons)
{
	if (buttons) {
		buttons->force_move_active = !buttons->force_move_active;
	}
}

static inline void MobileTouchButtons_ToggleRightClickMode(MobileTouchButtonState *buttons)
{
	if (buttons) {
		buttons->right_click_mode = !buttons->right_click_mode;
	}
}

static inline void MobileTouchButtons_ResetModifiers(MobileTouchButtonState *buttons)
{
	if (buttons) {
		buttons->force_attack_active = 0;
		buttons->force_move_active = 0;
		buttons->right_click_mode = 0;
	}
}

static inline int MobileTouchButtons_GetEffectiveVK(MobileTouchButtonState const *buttons, int default_vk)
{
	if (buttons && buttons->right_click_mode && default_vk == VK_LBUTTON) {
		return VK_RBUTTON;
	}
	return default_vk;
}

#endif
