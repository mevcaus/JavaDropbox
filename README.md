# JavaDropbox

> A lightweight, self-hosted file management solution built with Java and Spring Boot. Access, manage, and download your files from anywhere.

## Features

-   **File & Folder Downloads:**
    -   Download individual files directly.
    -   Download entire folders, which are automatically zipped on-the-fly.
-   **Uploads:** Easily add files by dragging them into the browser or using a traditional file selector.
-   **Configurable Directory:** Serve files from the default `JDB` subfolder or specify any directory on your system via a command-line argument.
-   **Springboot Security:** Authentication to restrict access to the file management interface.

##  Tech Stack

-   **Backend:**
    -   Java 21+
    -   Spring Boot 3+ (Spring Web)
    -   Thymeleaf
    -   Spring Security
-   **Frontend:**
    -   HTML5
    -   CSS3 
    -   Vanilla JavaScript (ES6+)
-   **Build Tool:**
    -   Gradle

##  Getting Started

Follow these instructions to get a local copy up and running.

### Prerequisites

You must have a Java Development Kit (JDK) installed on your system.
-   [JDK Version 21 or higher](https://www.oracle.com/java/technologies/downloads/)

### Installation & Running

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/mevcaus/JavaDropBox.git
    cd JavaDropBox
    ```

2.  **Build the project:**
    Use the Gradle wrapper to build the application. This will download all necessary dependencies.
    ```bash
    ./gradlew build
    ```

3.  **Run the application:**
    You have two options for running the server.

    **Option A: Default Mode**
    This will automatically create and serve files from a folder named `JDB` inside your project directory.
    ```bash
    ./gradlew bootRun
    ```

    **Option B: Custom Directory Mode**
    Use the `--args` flag to specify an absolute path to any directory you want to serve.
    ```bash
    ./gradlew bootRun --args='--directory=/path/to/your/files'
    ```
    *Example for Windows:*
    ```powershell
    ./gradlew bootRun --args='--directory=C:\Users\YourUser\Documents'
    ```

##  How to Use

### Accessing the Web UI

1.  Once the server is running, open your web browser and navigate to:
    **`http://localhost:8080`**

2.  Upon first access it will ask you to setup a username and password for authentication.

3.  After setting up your credentials, you will be redirected to the login screen. Use the credentials you just created to log in.


> note: if you forget your credentials, you can delete the `users.properties` file located in the project directory to reset them.

### Core Functionality

-   **Downloading:** Click directly on the name of any file or folder to start the download. Folders will be downloaded as a `.zip` file.
-   **Uploading:** Click the "Add Files" button. A modal will appear where you can either drag and drop files or click "Select Files" to open your system's file explorer.
-   **Deleting:** Click the trash can icon (🗑️) on the right side of any file or folder row. A confirmation pop-up will appear to prevent accidental deletion.

## Future Enhancements

Future plans for this project include:
-   [ ] **File Previews:** Implement previews for common file types (images, PDFs, text files).
-   [ ] **Search Functionality:** Add a search bar to quickly locate files and folders.
-   [ ] **Sorting Options:** Allow users to sort files and folders by name, date, size, etc.
-   [ ] **Upload to Subfolders:** Allow users to upload files directly into the currently expanded folder.
-   [ ] **Upload entire folders:** Allow users to upload folders directly into the currently expanded folder.
-   [ ] **Create New Folders:** Add a UI element to create new, empty directories.
-   [ ] **Desktop Folder Integration:** Create a desktop folder functionality that syncs a local folder with the server.

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
