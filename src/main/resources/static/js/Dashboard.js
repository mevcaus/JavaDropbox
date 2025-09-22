// --- STATE VARIABLES ---
let nodeIdCounter = 0;
let filesToUpload = [];
let pathToDelete = null;

// --- CORE FUNCTIONS ---
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

function toggleNode(nodeId) {
    const parentRow = document.querySelector(`tr[data-node-id='${nodeId}']`);
    const isCurrentlyExpanded = parentRow.classList.contains("expanded");

    if (isCurrentlyExpanded) {
        parentRow.classList.remove("expanded");
        collapseDescendants(nodeId);
    } else {
        parentRow.classList.add("expanded");
        const children = document.querySelectorAll(
            `.file-table tr[data-parent-id='${nodeId}']`
        );
        children.forEach((child) => {
            child.classList.remove("hidden");
        });
    }
}

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
        const nameLink = `<a href="${downloadUrl}" class="download-link" onclick="event.stopPropagation()">${node.name}</a>`;

        const deleteButton = `
            <td class="actions-cell">
                <button class="delete-btn" data-path="${currentPath}" data-name="${node.name}">
                    🗑️
                </button>
            </td>
        `;

        if (node.isDirectory) {
            html += `
                <tr class="collapsible ${isHidden}" 
                    data-node-id="${currentId}" 
                    data-parent-id="${parentId}" 
                    onclick="toggleNode(${currentId})">
                    <td><div class="file-name-cell" style="padding-left: ${indent}px;"><span class="icon-toggle"></span><span class="file-icon">📁</span>${nameLink}</div></td>
                    <td class="file-size">${sizeDisplay}</td>
                    ${deleteButton}
                </tr>
            `;
            if (node.children.length > 0) {
                html += renderTree(node.children, level + 1, currentId, currentPath);
            }
        } else {
            html += `
                <tr class="${isHidden}" data-parent-id="${parentId}">
                    <td><div class="file-name-cell" style="padding-left: ${
                indent + 15
            }px;"><span class="file-icon">📄</span>${nameLink}</div></td>
                    <td class="file-size">${sizeDisplay}</td>
                    ${deleteButton}
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
                <thead><tr><th>Name</th><th>Size</th><th class="actions-cell">Actions</th></tr></thead>
                <tbody>${tableBodyHtml}</tbody>
            </table>
        `;
    } catch (error) {
        console.error("Failed to load file tree:", error);
        container.innerHTML = `<p class="empty-message" style="color: red;">Error loading file tree.</p>`;
    }
}

function setupUploadModal() {
    const modal = document.getElementById("upload-modal");
    const addFilesBtn = document.getElementById("add-files-btn");
    const closeModalBtn = modal.querySelector(".close-button"); // Scoped
    const modalContent = modal.querySelector(".modal-content"); // Scoped - THIS IS THE FIX
    const dropZone = modal.querySelector("#drop-zone");
    const fileInput = modal.querySelector("#file-input");
    const selectFilesBtn = modal.querySelector("#select-files-btn");
    const uploadBtn = modal.querySelector("#upload-btn");
    const fileListPreview = modal.querySelector("#file-list-preview");
    const progressContainer = modal.querySelector("#upload-progress-container");
    const progressBar = modal.querySelector("#upload-progress-bar");

    // --- Event Listeners ---
    addFilesBtn.addEventListener("click", () => modal.classList.remove("hidden"));
    closeModalBtn.addEventListener("click", closeModal); // We don't need stopPropagation here anymore

    modal.addEventListener("click", (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    modalContent.addEventListener("click", (e) => { // This now correctly targets the upload modal's content
        e.stopPropagation();
    });

    selectFilesBtn.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", () => handleFiles(fileInput.files));
    uploadBtn.addEventListener("click", uploadFiles);

    dropZone.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropZone.classList.add("drag-over");
    });
    dropZone.addEventListener("dragleave", () =>
        dropZone.classList.remove("drag-over")
    );
    dropZone.addEventListener("drop", (e) => {
        e.preventDefault();
        dropZone.classList.remove("drag-over");
        handleFiles(e.dataTransfer.files);
    });

    // --- Helper Functions ---
    function handleFiles(newFiles) {
        for (const file of newFiles) {
            filesToUpload.push(file);
        }
        updateFileListUI();
    }

    function updateFileListUI() {
        fileListPreview.innerHTML = "";
        if (filesToUpload.length > 0) {
            filesToUpload.forEach((file) => {
                const item = document.createElement("div");
                item.className = "file-list-item";
                item.textContent = `${file.name} (${formatFileSize(file.size)})`;
                fileListPreview.appendChild(item);
            });
            uploadBtn.disabled = false;
        } else {
            uploadBtn.disabled = true;
        }
    }

    async function uploadFiles() {
        if (filesToUpload.length === 0) return;
        const formData = new FormData();
        formData.append("path", "");
        filesToUpload.forEach((file) => formData.append("files", file));
        progressContainer.classList.remove("hidden");
        progressBar.style.width = "0%";
        uploadBtn.disabled = true;
        try {
            progressBar.style.width = "50%"; // Simulate progress
            const response = await fetch("/api/upload", {
                method: "POST",
                body: formData,
            });
            progressBar.style.width = "100%";
            if (response.ok) {
                closeModal();
                loadFileTree();
            } else {
                const error = await response.json();
                alert(`Upload failed: ${error.message}`);
                progressContainer.classList.add("hidden");
                uploadBtn.disabled = false;
            }
        } catch (error) {
            alert(`An error occurred: ${error.message}`);
            progressContainer.classList.add("hidden");
            uploadBtn.disabled = false;
        }
    }

    function closeModal() {
        modal.classList.add("hidden");
        filesToUpload = [];
        updateFileListUI();
        progressContainer.classList.add("hidden");
        progressBar.style.width = "0%";
    }
}

function setupDeleteModal() {
    const deleteModal = document.getElementById("delete-confirm-modal");
    const modalContent = deleteModal.querySelector(".modal-content"); // Scoped query
    const fileBrowser = document.getElementById("file-browser-container");
    const cancelBtn = deleteModal.querySelector("#cancel-delete-btn");
    const confirmBtn = deleteModal.querySelector("#confirm-delete-btn");
    const itemNameEl = deleteModal.querySelector("#item-to-delete-name");

    fileBrowser.addEventListener("click", (e) => {
        if (e.target && e.target.closest(".delete-btn")) {
            e.stopPropagation();
            const button = e.target.closest(".delete-btn");
            pathToDelete = button.dataset.path;
            itemNameEl.textContent = button.dataset.name;
            deleteModal.classList.remove("hidden");
        }
    });

    const closeDeleteModal = () => {
        deleteModal.classList.add("hidden");
        pathToDelete = null;
    };

    cancelBtn.addEventListener("click", closeDeleteModal);
    deleteModal.addEventListener("click", (e) => {
        if (e.target === deleteModal) {
            closeDeleteModal();
        }
    });
    modalContent.addEventListener("click", (e) => {
        e.stopPropagation();
    });

    // Handle the confirmation click
    confirmBtn.addEventListener("click", async () => {
        if (!pathToDelete) return;

        try {
            const response = await fetch(
                `/api/delete?path=${encodeURIComponent(pathToDelete)}`, {
                    method: "DELETE",
                }
            );
            if (response.ok) {
                loadFileTree(); //
            } else {
                const error = await response.json();
                alert(`Error: ${error.message}`);
            }
        } catch (error) {
            alert("An unexpected error occurred.");
            console.error("Delete error:", error);
        } finally {
            closeDeleteModal();
        }
    });
}

// --- PAGE INITIALIZATION ---
document.addEventListener("DOMContentLoaded", () => {
    populateUserInfo();
    loadFileTree();
    setupUploadModal();
    setupDeleteModal();
});