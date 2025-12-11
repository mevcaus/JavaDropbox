// --- STATE VARIABLES ---
let nodeIdCounter = 0;
let filesToUpload = [];
let pathToDelete = null;
let targetUploadPath = "";
let targetCreateFolderPath = "";

// --- CORE FUNCTIONS ---
function formatFileSize(bytes) {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

function toggleNode(event, nodeId, isDirectory, nodeData) {
    // Prevent toggling if a button or an element inside a button was clicked
    if (event && event.target.closest('button')) {
        return;
    }

    if (!isDirectory) {
        // It's a file, show details
        showFileDetails(nodeData);
        return;
    }

    const parentRow = document.querySelector(`tr[data-node-id='${nodeId}']`);
    const isCurrentlyExpanded = parentRow.classList.contains("expanded");

    if (isCurrentlyExpanded) {
        parentRow.classList.remove("expanded");
        const descendants = document.querySelectorAll(`.child-of-${nodeId}`);
        descendants.forEach((desc) => {
            desc.classList.add("hidden");
            if (desc.classList.contains("expanded")) {
                desc.classList.remove("expanded");
            }
        });
    } else {
        parentRow.classList.add("expanded");
        const children = document.querySelectorAll(
            `tr[data-parent-id='${nodeId}']`
        );
        children.forEach((child) => {
            child.classList.remove("hidden");
        });
    }
}

function showFileDetails(node) {
    const modal = document.getElementById("file-details-modal");

    // Populate Modal
    document.getElementById("detail-filename").textContent = node.name;
    document.getElementById("detail-type").textContent = node.isDirectory ? "Folder" : (node.name.split('.').pop().toUpperCase() + " File");
    document.getElementById("detail-size").textContent = formatFileSize(node.size);
    document.getElementById("detail-created").textContent = node.createdDate || "Unknown";
    document.getElementById("detail-modified").textContent = node.lastModified || "Unknown";
    document.getElementById("detail-owner").textContent = node.ownerName || "Unknown";

    // Setup Download Link
    const downloadBtn = document.getElementById("detail-download-btn");

}


// --- HELPER --
function getFileIcon(name, isDirectory) {
    if (isDirectory) return "/images/icons/folder.png";
    const ext = name.split('.').pop().toLowerCase();
    switch (ext) {
        case 'java': return "/images/icons/java.png";
        case 'html': return "/images/icons/html.png";
        case 'css': return "/images/icons/css.png";
        case 'js': return "/images/icons/js.png";
        case 'png':
        case 'jpg':
        case 'jpeg':
        case 'gif': return "/images/icons/image.png";
        default: return "/images/icons/file.png";
    }
}

function renderTree(nodes, level, ancestorIds, parentPath) {
    let html = "";
    nodes.forEach((node) => {
        const currentId = ++nodeIdCounter;
        const parentId = ancestorIds[ancestorIds.length - 1];
        const isHidden = level > 0 ? "hidden" : "";
        const indent = level * 25;
        const sizeDisplay = formatFileSize(node.size);
        const currentPath = [parentPath, node.name].filter(Boolean).join("/");
        const encodedPath = encodeURIComponent(currentPath);
        const downloadUrl = `/api/download?path=${encodedPath}`;

        // Pass essential data + path to the handler
        // Escaping strings for onclick is annoying, so we'll store data in attributes and read it in the handler
        const nodeJson = JSON.stringify({ ...node, fullPath: currentPath }).replace(/"/g, '&quot;');

        const nameLink = `<span class="file-name-span">${node.name}</span>`;

        const ancestorClasses = ancestorIds
            .map((id) => `child-of-${id}`)
            .join(" ");

        const iconSrc = getFileIcon(node.name, node.isDirectory);
        const iconImg = `<img src="${iconSrc}" class="file-icon-img" style="width: 20px; height: 20px; vertical-align: middle; margin-right: 5px;" alt="icon"/>`;

        const dateDisplay = node.createdDate || "-";

        if (node.isDirectory) {
            const uploadButton = `<button class="upload-btn" data-path="${currentPath}" data-name="${node.name}" title="Upload files to this directory"><img src="/images/icons/file-upload.png" style="width: 20px; height: 20px;" alt="Upload"/></button>`;
            const createFolderButton = `<button class="create-folder-btn" data-path="${currentPath}" data-name="${node.name}" title="Create folder in this directory"><img src="/images/icons/create-folder.png" style="width: 20px; height: 20px;" alt="New Folder"/></button>`;
            const deleteButton = `<button class="delete-btn" data-path="${currentPath}" data-name="${node.name}" title="Delete this directory"><img src="/images/icons/delete-folder.png" style="width: 20px; height: 20px;" alt="Delete"/></button>`;
            const actionsCell = `<td class="actions-cell">${createFolderButton}${uploadButton}${deleteButton}</td>`;
            html += `
                <tr class="collapsible ${isHidden} ${ancestorClasses}" 
                    data-node-id="${currentId}" 
                    data-parent-id="${parentId}"
                    onclick="toggleNode(event, ${currentId}, true, null)">
                    <td><div class="file-name-cell" style="padding-left: ${indent}px;"><span class="icon-toggle"></span>${iconImg}${nameLink}</div></td>
                    <td>${dateDisplay}</td>
                    <td class="file-size">${sizeDisplay}</td>
                    ${actionsCell}
                </tr>
            `;
            if (node.children.length > 0) {
                const newAncestorIds = [...ancestorIds, currentId];
                html += renderTree(
                    node.children,
                    level + 1,
                    newAncestorIds,
                    currentPath
                );
            }
        } else {
            const deleteButton = `<button class="delete-btn" data-path="${currentPath}" data-name="${node.name}" title="Delete this file"><img src="/images/icons/delete-file.png" style="width: 20px; height: 20px;" alt="Delete"/></button>`;
            const actionsCell = `<td class="actions-cell">${deleteButton}</td>`;

            html += `
                <tr class="${isHidden} ${ancestorClasses} file-row" 
                    data-parent-id="${parentId}"
                    onclick="toggleNode(event, ${currentId}, false, ${nodeJson})">
                    <td><div class="file-name-cell" style="padding-left: ${indent + 15
                }px;">${iconImg}${nameLink}</div></td>
                    <td>${dateDisplay}</td>
                    <td class="file-size">${sizeDisplay}</td>
                    ${actionsCell}
                </tr>
            `;
        }
    });
    return html;
}

function showFileDetails(node) {
    const modal = document.getElementById("file-details-modal");

    document.getElementById("detail-filename").textContent = node.name;
    document.getElementById("detail-type").textContent = node.name.split('.').pop().toUpperCase() + " File";
    document.getElementById("detail-size").textContent = formatFileSize(node.size);
    document.getElementById("detail-created").textContent = node.createdDate || "Unknown";
    document.getElementById("detail-modified").textContent = node.lastModified || "Unknown";
    document.getElementById("detail-owner").textContent = node.ownerName || "Unknown";

    const downloadBtn = document.getElementById("detail-download-btn");
    const encodedPath = encodeURIComponent(node.fullPath);
    downloadBtn.href = `/api/download?path=${encodedPath}`;

    modal.classList.remove("hidden");

    // Close handlers
    const closeBtn = document.getElementById("close-file-details-btn");
    const closeBtn2 = document.getElementById("close-details-secondary-btn");

    const closeHandler = () => modal.classList.add("hidden");

    closeBtn.onclick = closeHandler;
    closeBtn2.onclick = closeHandler;
}


async function loadFileTree() {
    const container = document.getElementById("file-browser-container");
    container.innerHTML = `<p>Loading file tree...</p>`;
    try {
        const response = await fetch("/api/files");
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const fileTree = await response.json();

        nodeIdCounter = 0;
        let tableBodyHtml = "";

        if (fileTree.length === 0) {
            container.innerHTML = `
                <table class="file-table">
                    <thead><tr><th>Name</th><th>Date Created</th><th>Size</th><th class="actions-cell">Actions</th></tr></thead>
                    <tbody>${tableBodyHtml}</tbody>
                </table>
                <p class="empty-message">This directory is empty. Use the upload button above to add files.</p>
            `;
            return;
        }

        tableBodyHtml += renderTree(fileTree, 0, ["root"], "");
        container.innerHTML = `
            <table class="file-table">
                <thead><tr><th>Name</th><th>Date Created</th><th>Size</th><th class="actions-cell">Actions</th></tr></thead>
                <tbody>${tableBodyHtml}</tbody>
            </table>
        `;
    } catch (error) {
        console.error("Failed to load file tree:", error);
        container.innerHTML = `<p class="empty-message" style="color: red;">Error loading file tree.</p>`;
    }
}

function openUploadModal(targetPath = "", displayName = "Root Directory") {
    targetUploadPath = targetPath;
    const modal = document.getElementById("upload-modal");
    const targetPathDisplay = modal.querySelector("#target-path-display");

    targetPathDisplay.textContent = displayName;
    modal.classList.remove("hidden");
}

function setupUploadModal() {
    const modal = document.getElementById("upload-modal");
    const addFilesBtn = document.getElementById("add-files-btn");
    const closeModalBtn = modal.querySelector(".close-button");
    const modalContent = modal.querySelector(".modal-content");
    const dropZone = modal.querySelector("#drop-zone");
    const fileInput = modal.querySelector("#file-input");
    const selectFilesBtn = modal.querySelector("#select-files-btn");
    const uploadBtn = modal.querySelector("#upload-btn");
    const fileListPreview = modal.querySelector("#file-list-preview");
    const progressContainer = modal.querySelector("#upload-progress-container");
    const progressBar = modal.querySelector("#upload-progress-bar");
    const fileBrowser = document.getElementById("file-browser-container");

    // --- Event Listeners ---
    addFilesBtn.addEventListener("click", () => openUploadModal());
    closeModalBtn.addEventListener("click", closeModal);

    fileBrowser.addEventListener("click", (e) => {
        if (e.target && e.target.closest(".upload-btn")) {
            // current bug does not stop expanding of folder when clicking upload button
            e.stopPropagation();
            const button = e.target.closest(".upload-btn");
            const path = button.dataset.path;
            const name = button.dataset.name;
            const displayName = path === "" ? "Root Directory" : `${name}/`;
            openUploadModal(path, displayName);
        }
    });

    modal.addEventListener("click", (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    modalContent.addEventListener("click", (e) => {
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
        formData.append("path", targetUploadPath); // Use the selected target path
        filesToUpload.forEach((file) => formData.append("files", file));
        progressContainer.classList.remove("hidden");
        progressBar.style.width = "0%";
        uploadBtn.disabled = true;
        try {
            progressBar.style.width = "50%";
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
        targetUploadPath = "";
        updateFileListUI();
        progressContainer.classList.add("hidden");
        progressBar.style.width = "0%";
    }
}

function setupDeleteModal() {
    const deleteModal = document.getElementById("delete-confirm-modal");
    const modalContent = deleteModal.querySelector(".modal-content");
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

    confirmBtn.addEventListener("click", async () => {
        if (!pathToDelete) return;

        try {
            const response = await fetch(
                `/api/delete?path=${encodeURIComponent(pathToDelete)}`, {
                method: "DELETE",
            }
            );
            if (response.ok) {
                loadFileTree();
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

function setupCreateFolderModal() {
    const modal = document.getElementById("create-folder-modal");
    const modalContent = modal.querySelector(".modal-content");
    const createFolderBtn = document.getElementById("create-folder-btn");
    const closeBtn = modal.querySelector("#close-create-folder-btn");
    const cancelBtn = modal.querySelector("#cancel-create-folder-btn");
    const confirmBtn = modal.querySelector("#confirm-create-folder-btn");
    const folderNameInput = modal.querySelector("#folder-name-input");
    const targetDisplay = modal.querySelector("#create-folder-target-display");
    const fileBrowser = document.getElementById("file-browser-container");

    const openCreateFolderModal = (targetPath = "", displayName = "Root Directory") => {
        targetCreateFolderPath = targetPath;
        targetDisplay.textContent = displayName;
        folderNameInput.value = "";
        modal.classList.remove("hidden");
        folderNameInput.focus();
    };

    const closeCreateFolderModal = () => {
        modal.classList.add("hidden");
        targetCreateFolderPath = "";
        folderNameInput.value = "";
    };

    createFolderBtn.addEventListener("click", () => openCreateFolderModal());

    fileBrowser.addEventListener("click", (e) => {
        if (e.target && e.target.closest(".create-folder-btn")) {
            e.stopPropagation();
            const button = e.target.closest(".create-folder-btn");
            const path = button.dataset.path;
            const name = button.dataset.name;
            const displayName = path === "" ? "Root Directory" : `${name}/`;
            openCreateFolderModal(path, displayName);
        }
    });

    closeBtn.addEventListener("click", closeCreateFolderModal);
    cancelBtn.addEventListener("click", closeCreateFolderModal);

    modal.addEventListener("click", (e) => {
        if (e.target === modal) {
            closeCreateFolderModal();
        }
    });

    modalContent.addEventListener("click", (e) => {
        e.stopPropagation();
    });

    folderNameInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") {
            confirmBtn.click();
        }
    });

    confirmBtn.addEventListener("click", async () => {
        const folderName = folderNameInput.value.trim();

        if (!folderName) {
            alert("Please enter a folder name");
            return;
        }

        if (folderName.includes("/") || folderName.includes("\\") || folderName.includes("..")) {
            alert("Invalid folder name. Cannot contain /, \\, or ..");
            return;
        }

        try {
            const response = await fetch("/api/create-directory", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded",
                },
                body: new URLSearchParams({
                    path: targetCreateFolderPath,
                    name: folderName,
                }),
            });

            if (response.ok) {
                closeCreateFolderModal();
                loadFileTree();
            } else {
                const error = await response.json();
                alert(`Error: ${error.message}`);
            }
        } catch (error) {
            alert("An unexpected error occurred.");
            console.error("Create folder error:", error);
        }
    });
}

// --- PAGE INITIALIZATION ---
document.addEventListener("DOMContentLoaded", () => {
    loadFileTree();
    setupUploadModal();
    setupDeleteModal();
    setupCreateFolderModal();
});