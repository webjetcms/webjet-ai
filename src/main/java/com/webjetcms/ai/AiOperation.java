package com.webjetcms.ai;

/** Type of provider operation requested by a caller. */
public enum AiOperation {
    /** Produces a text response from textual and optional binary input. */
    TEXT,

    /** Creates one or more images from a text prompt. */
    GENERATE_IMAGE,

    /** Edits an input image according to a text prompt. */
    EDIT_IMAGE
}
