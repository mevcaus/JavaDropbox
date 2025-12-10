document.addEventListener("DOMContentLoaded", () => {
    loadHistory();
});

async function loadHistory() {
    const container = document.getElementById("history-container");
    container.innerHTML = `<p>Loading history...</p>`;

    try {
        const response = await fetch("/api/history");
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

        const historyData = await response.json();

        if (historyData.length === 0) {
            container.innerHTML = `<p class="empty-message">No history available.</p>`;
            return;
        }

        let tableHtml = `
            <table class="file-table history-table">
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>User</th>
                        <th>Action</th>
                        <th>File/Path</th>
                        <th>Status</th>
                        <th>Details</th>
                    </tr>
                </thead>
                <tbody>
        `;

        historyData.forEach(item => {
            const date = new Date(item.timestamp).toLocaleString();
            const user = item.username || "Unknown";
            const action = item.changeType;
            const path = item.filePath || item.filename || "-";
            const statusClass = item.success ? "success" : "failure";
            const statusText = item.success ? "Success" : "Failed";
            const details = item.errorMessage ? `<span class="error-message">${item.errorMessage}</span>` : "-";

            tableHtml += `
                <tr>
                    <td>${date}</td>
                    <td>${user}</td>
                    <td>${action}</td>
                    <td>${path}</td>
                    <td class="${statusClass}">${statusText}</td>
                    <td>${details}</td>
                </tr>
            `;
        });

        tableHtml += `</tbody></table>`;
        container.innerHTML = tableHtml;

    } catch (error) {
        console.error("Failed to load history:", error);
        container.innerHTML = `<p class="empty-message" style="color: red;">Error loading history.</p>`;
    }
}
