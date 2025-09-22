let nodeIdCounter = 0; // A unique ID for each node

function populateUserInfo() {
    const urlParams = new URLSearchParams(window.location.search);
    const username = urlParams.get("user");
    if (username) document.getElementById("username").textContent = username;

    const directory = urlParams.get("directory");
    if (directory) {
        document.getElementById("current-path-text").textContent = directory;
    } else {
        document.getElementById("current-path-text").textContent =
            "Directory path not found.";
    }
}

function formatFileSize(bytes) {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

/**
 * Recursively finds all descendants of a node and hides them.
 * Also resets their state to "collapsed".
 * @param {number} parentId - The node ID to start collapsing from.
 */
function collapseDescendants(parentId) {
    const children = document.querySelectorAll(
        `.file-table tr[data-parent-id='${parentId}']`
    );
    children.forEach((child) => {
        child.classList.add("hidden");

        if (child.classList.contains("collapsible")) {
            child.classList.remove("expanded");

            const childNodeId = child.dataset.nodeId;
            if (childNodeId) {
                collapseDescendants(childNodeId);
            }
        }
    });
}

/**
 * Toggles the visibility of direct children, or triggers a deep collapse.
 * @param {number} nodeId - The unique ID of the parent node that was clicked.
 */
function toggleNode(nodeId) {
    const parentRow = document.querySelector(`tr[data-node-id='${nodeId}']`);
    const isCurrentlyExpanded = parentRow.classList.contains("expanded");

    if (isCurrentlyExpanded) {
        // --- COLLAPSING ---
        parentRow.classList.remove("expanded");
        collapseDescendants(nodeId); // Use the new recursive helper function
    } else {
        // --- EXPANDING ---
        parentRow.classList.add("expanded");
        const children = document.querySelectorAll(
            `.file-table tr[data-parent-id='${nodeId}']`
        );
        children.forEach((child) => {
            child.classList.remove("hidden");
        });
    }
}

/**
 * Recursively builds the HTML for the file tree, now with download links.
 * @param {Array} nodes - The array of file nodes.
 * @param {number} level - The current indentation level.
 * @param {number} parentId - The ID of the parent node.
 * @param {string} parentPath - The relative path of the parent directory.
 * @returns {string} The generated HTML string for the table rows.
 */
function renderTree(nodes, level, parentId, parentPath) {
    let html = "";
    nodes.forEach((node) => {
        const currentId = ++nodeIdCounter;
        const isHidden = level > 0 ? "hidden" : "";
        const indent = level * 25;
        const sizeDisplay = formatFileSize(node.size);

        const currentPath = [parentPath, node.name].filter(Boolean).join("/");
        const encodedPath = encodeURIComponent(currentPath);
        const downloadUrl = `/api/download?path=${encodedPath}`;

        // Create the download link
        const nameLink = `<a href="${downloadUrl}" class="download-link">${node.name}</a>`;

        if (node.isDirectory) {
            html += `
                <tr class="collapsible ${isHidden}" 
                    data-node-id="${currentId}" 
                    data-parent-id="${parentId}" 
                    onclick="toggleNode(${currentId})">
                    <td>
                        <div class="file-name-cell" style="padding-left: ${indent}px;">
                            <span class="icon-toggle"></span>
                            <span class="file-icon">📁</span>
                            ${nameLink}
                        </div>
                    </td>
                    <td class="file-size">${sizeDisplay}</td>
                </tr>
            `;
            if (node.children.length > 0) {
                // Pass the new currentPath down to the children
                html += renderTree(node.children, level + 1, currentId, currentPath);
            }
        } else {
            html += `
                <tr class="${isHidden}" data-parent-id="${parentId}">
                    <td>
                        <div class="file-name-cell" style="padding-left: ${
                indent + 15
            }px;">
                            <span class="file-icon">📄</span>
                            ${nameLink}
                        </div>
                    </td>
                    <td class="file-size">${sizeDisplay}</td>
                </tr>
            `;
        }
    });
    return html;
}

async function loadFileTree() {
    const container = document.getElementById("file-browser-container");
    container.innerHTML = `<p>Loading file tree...</p>`;
    try {
        const response = await fetch("/api/files");
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const fileTree = await response.json();
        if (fileTree.length === 0) {
            container.innerHTML = `<p class="empty-message">This directory is empty.</p>`;
            return;
        }
        nodeIdCounter = 0;
        const tableBodyHtml = renderTree(fileTree, 0, "root", "");
        container.innerHTML = `
            <table class="file-table">
                <thead><tr><th>Name</th><th>Size</th></tr></thead>
                <tbody>${tableBodyHtml}</tbody>
            </table>
        `;
    } catch (error) {
        console.error("Failed to load file tree:", error);
        container.innerHTML = `<p class="empty-message" style="color: red;">Error loading file tree.</p>`;
    }
}

document.addEventListener("DOMContentLoaded", () => {
    populateUserInfo();
    loadFileTree();
});