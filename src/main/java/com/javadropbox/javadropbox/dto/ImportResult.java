package com.javadropbox.javadropbox.dto;

/**
 * What an import found in an account's folder.
 *
 * @param files files that had no row and now have one
 * @param folders folders that had no row and now have one
 * @param missing rows whose file or folder is not in the store, which the import leaves alone
 * @param skipped items whose name is too long to keep, left out along with what they hold
 */
public record ImportResult(int files, int folders, int missing, int skipped) {}
