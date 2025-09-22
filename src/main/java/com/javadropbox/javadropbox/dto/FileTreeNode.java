package com.javadropbox.javadropbox.dto;

import java.util.List;
import java.util.ArrayList;

public class FileTreeNode {
    private String name;
    private boolean isDirectory;
    private long size;
    private List<FileTreeNode> children; // The crucial new property

    public FileTreeNode(String name, boolean isDirectory, long size) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.size = size;
        this.children = new ArrayList<>(); // Initialize to avoid nulls
    }

    // --- Getters and Setters ---
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean getIsDirectory() { return isDirectory; }
    public void setIsDirectory(boolean isDirectory) { this.isDirectory = isDirectory; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public List<FileTreeNode> getChildren() { return children; }
    public void setChildren(List<FileTreeNode> children) { this.children = children; }
}