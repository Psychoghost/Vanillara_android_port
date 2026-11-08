#include "mobile_touch_buttons.h"

#include <assert.h>

static void test_touch_buttons_init(void)
{
	MobileTouchButtonState buttons;
	MobileTouchButtons_Init(&buttons);
	assert(!buttons.force_attack_active);
	assert(!buttons.force_move_active);
	assert(!buttons.right_click_mode);
}

static void test_touch_buttons_toggles(void)
{
	MobileTouchButtonState buttons;
	MobileTouchButtons_Init(&buttons);

	MobileTouchButtons_ToggleForceAttack(&buttons);
	assert(buttons.force_attack_active);

	MobileTouchButtons_ToggleForceMove(&buttons);
	assert(buttons.force_move_active);

	MobileTouchButtons_ToggleRightClickMode(&buttons);
	assert(buttons.right_click_mode);

	assert(MobileTouchButtons_GetEffectiveVK(&buttons, VK_LBUTTON) == VK_RBUTTON);

	MobileTouchButtons_ResetModifiers(&buttons);
	assert(!buttons.force_attack_active);
	assert(!buttons.force_move_active);
	assert(!buttons.right_click_mode);
	assert(MobileTouchButtons_GetEffectiveVK(&buttons, VK_LBUTTON) == VK_LBUTTON);
}

int main(void)
{
	test_touch_buttons_init();
	test_touch_buttons_toggles();
	return 0;
}
