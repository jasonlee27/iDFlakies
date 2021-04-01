/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.cs.dt.tools.constants;

import java.io.File;

/**
 * Some constants used throughout the STARTS codebase.
 */
public interface Constants {
    String IDFLAKIES_DIRECTORY_PATH = ".dtfixingtools" + File.separator;
    String ORIGINAL_ORDER = IDFLAKIES_DIRECTORY_PATH + "original-order";
    String SELECTED_ORDER = IDFLAKIES_DIRECTORY_PATH + "selected-order";
    String ORIGINAL_TIME = IDFLAKIES_DIRECTORY_PATH + "original-order-runningtime";
    String SELECTED_TIME = IDFLAKIES_DIRECTORY_PATH + "selected-order-runningtime";
    String SEC = " sec";
}
