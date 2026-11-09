#ifndef MOBILE_DPAD_H
#define MOBILE_DPAD_H

enum MobileDpadDir { DPAD_NONE = 0, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT };

struct MobileDpad {
	int cx, cy, radius;      /* window pixels */
	int active;              /* a finger owns the pad */
	long long finger;
	int dir;                 /* MobileDpadDir */
	unsigned long last_tick;
	unsigned long repeat_ms;
};

static inline void MobileDpad_Init(MobileDpad *d)
{
	if (!d) return;
	d->cx = d->cy = d->radius = 0;
	d->active = 0;
	d->finger = 0;
	d->dir = DPAD_NONE;
	d->last_tick = 0;
	d->repeat_ms = 40;
}

/* Bottom-left corner. Recomputed from the live window size. */
static inline void MobileDpad_Layout(MobileDpad *d, int window_w, int window_h)
{
	if (!d) return;
	int shorter = window_w < window_h ? window_w : window_h;
	d->radius = shorter / 7;
	int margin = d->radius / 3;
	d->cx = margin + d->radius;
	d->cy = window_h - margin - d->radius;
}

static inline int MobileDpad_Classify(MobileDpad const *d, float x, float y)
{
	if (!d || d->radius <= 0) return DPAD_NONE;
	float dx = x - (float)d->cx;
	float dy = y - (float)d->cy;
	float outer = (float)d->radius * 1.15f;
	float dead = (float)d->radius * 0.25f;
	float dist2 = dx * dx + dy * dy;
	if (dist2 > outer * outer || dist2 < dead * dead) return DPAD_NONE;
	float ax = dx < 0 ? -dx : dx;
	float ay = dy < 0 ? -dy : dy;
	if (ax > ay) return dx < 0 ? DPAD_LEFT : DPAD_RIGHT;
	return dy < 0 ? DPAD_UP : DPAD_DOWN;
}

/* Returns 1 if the touch landed on the pad and the pad now owns the finger. */
static inline int MobileDpad_Down(MobileDpad *d, long long finger, float x, float y, unsigned long tick)
{
	if (!d || d->active) return 0;
	float dx = x - (float)d->cx;
	float dy = y - (float)d->cy;
	float outer = (float)d->radius * 1.15f;
	if (d->radius <= 0 || dx * dx + dy * dy > outer * outer) return 0;
	d->active = 1;
	d->finger = finger;
	d->dir = MobileDpad_Classify(d, x, y);
	d->last_tick = tick - d->repeat_ms; /* fire on first tick */
	return 1;
}

static inline int MobileDpad_Owns(MobileDpad const *d, long long finger)
{
	return d && d->active && d->finger == finger;
}

static inline void MobileDpad_Move(MobileDpad *d, long long finger, float x, float y)
{
	if (MobileDpad_Owns(d, finger)) d->dir = MobileDpad_Classify(d, x, y);
}

static inline void MobileDpad_Up(MobileDpad *d, long long finger)
{
	if (MobileDpad_Owns(d, finger)) {
		d->active = 0;
		d->dir = DPAD_NONE;
	}
}

/* Returns direction to pulse this tick, or DPAD_NONE. */
static inline int MobileDpad_Tick(MobileDpad *d, unsigned long tick)
{
	if (!d || !d->active || d->dir == DPAD_NONE) return DPAD_NONE;
	if ((unsigned long)(tick - d->last_tick) < d->repeat_ms) return DPAD_NONE;
	d->last_tick = tick;
	return d->dir;
}

#endif
