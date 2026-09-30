package org.telegram.messenger.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.constraints.ConstraintManager;

final class CarListLimits {

    private static final int FALLBACK_LIST_LIMIT = 6;
    static final int LOAD_MORE_STEP = 20;

    private CarListLimits() {
    }

    /** Maximum number of rows the connected host shows in a single list. */
    static int getListLimit(@NonNull CarContext carContext) {
        try {
            if (carContext.getCarAppApiLevel() >= 2) {
                ConstraintManager manager = carContext.getCarService(ConstraintManager.class);
                return Math.max(1, manager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST));
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_LIST_LIMIT;
    }

    /** Initial number of content rows, leaving room for the given number of extra rows. */
    static int initialCount(@NonNull CarContext carContext, int reservedRows) {
        return Math.max(1, Math.min(LOAD_MORE_STEP, getListLimit(carContext) - reservedRows));
    }

    /** Largest number of content rows that still fits next to the given number of extra rows. */
    static int maxCount(@NonNull CarContext carContext, int reservedRows) {
        return Math.max(1, getListLimit(carContext) - reservedRows);
    }
}
