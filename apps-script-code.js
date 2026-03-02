/**
 * RouteMe Google Sheets Write-Back Script
 *
 * DEPLOYMENT INSTRUCTIONS:
 * 1. Open your Google Sheet
 * 2. Extensions -> Apps Script
 * 3. Paste this code (replace any existing code)
 * 4. Click "Deploy" -> "New deployment"
 * 5. Type: "Web app"
 * 6. Execute as: "Me"
 * 7. Who has access: "Anyone" (important for the Android app to work!)
 * 8. Click "Deploy" and copy the URL
 * 9. Paste the URL into RouteMe's "Apps Script URL" field
 *
 * IMPORTANT: After any code changes, you must create a NEW deployment
 * (not just save). The URL will change each time.
 */

function testAuth() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheets()[0];
  Logger.log("Sheet name: " + sheet.getName());
  Logger.log("Headers: " + sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]);
}

/**
 * Test the update logic without needing an HTTP request.
 * Run this from the Apps Script editor to debug.
 *
 * NOTE: Change the clientName to an actual client in your sheet!
 */
function testUpdate() {
  var testData = {
    clientName: "Wagner, Mary",  // <- Change to a real client name
    column: "Step 1",
    value: "√3.1"
  };

  var result = updateCell(testData);
  Logger.log("Result: " + JSON.stringify(result));
}

/**
 * Dry-run test - finds the client and column but doesn't write anything.
 * Useful to verify matching works without modifying data.
 */
function testFindOnly() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheets()[0];

  // Log all headers
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  Logger.log("Headers: " + headers.join(" | "));

  // Log first 10 client names
  var names = sheet.getRange(2, 1, Math.min(10, sheet.getLastRow() - 1), 1).getValues();
  Logger.log("First 10 clients:");
  for (var i = 0; i < names.length; i++) {
    Logger.log("  Row " + (i + 2) + ": " + names[i][0]);
  }
}

/**
 * Handles GET requests - now also handles updates via URL parameters
 * This works better because POST body gets lost on Apps Script redirects
 */
function doGet(e) {
  try {
    // Check if this is an update request (has clientName, column, value params)
    if (e.parameter && e.parameter.clientName && e.parameter.column && e.parameter.value) {
      Logger.log("doGet update request: " + JSON.stringify(e.parameter));

      var data = {
        clientName: e.parameter.clientName,
        column: e.parameter.column,
        value: e.parameter.value
      };

      var result = updateCell(data);
      return jsonResponse(result);
    }

    // Otherwise just return status
    return jsonResponse({
      status: "ok",
      message: "RouteMe Apps Script is running. Send clientName, column, and value parameters to update.",
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    Logger.log("doGet error: " + error.message);
    return jsonResponse({status: "error", message: "Script error: " + error.message});
  }
}

/**
 * Handles POST requests from the RouteMe Android app
 */
function doPost(e) {
  try {
    // Log the raw request for debugging
    Logger.log("doPost called");
    Logger.log("postData: " + (e.postData ? e.postData.contents : "none"));

    if (!e.postData || !e.postData.contents) {
      return jsonResponse({status: "error", message: "No POST data received"});
    }

    var data;
    try {
      data = JSON.parse(e.postData.contents);
    } catch (parseError) {
      return jsonResponse({status: "error", message: "Invalid JSON: " + parseError.message});
    }

    // Validate required fields
    if (!data.clientName || !data.column || !data.value) {
      return jsonResponse({
        status: "error",
        message: "Missing required fields. Need: clientName, column, value"
      });
    }

    var result = updateCell(data);
    return jsonResponse(result);

  } catch (error) {
    Logger.log("doPost error: " + error.message);
    return jsonResponse({status: "error", message: "Script error: " + error.message});
  }
}

/**
 * Core logic to find and update a cell
 */
function updateCell(data) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheets()[0];

  // Find the column by header name
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var colIndex = -1;
  var searchColumn = data.column.toString().trim().toLowerCase();

  for (var i = 0; i < headers.length; i++) {
    var header = headers[i].toString().trim().toLowerCase();
    // Remove checkmarks or other symbols from header comparison
    header = header.replace(/[√✓]/g, "").trim();

    if (header === searchColumn) {
      colIndex = i + 1;  // Sheets are 1-indexed
      Logger.log("Found column '" + data.column + "' at index " + colIndex);
      break;
    }
  }

  if (colIndex === -1) {
    Logger.log("Column not found. Looking for: '" + data.column + "'");
    Logger.log("Available headers: " + headers.join(", "));
    return {
      status: "error",
      message: "Column not found: " + data.column,
      availableColumns: headers.filter(function(h) { return h.toString().trim() !== ""; })
    };
  }

  // Find the row by client name (case-insensitive, trimmed)
  var names = sheet.getRange(1, 1, sheet.getLastRow(), 1).getValues();
  var rowIndex = -1;
  var searchName = data.clientName.toString().trim().toLowerCase();

  for (var r = 1; r < names.length; r++) {  // Skip header row
    var cellName = names[r][0].toString().trim().toLowerCase();
    if (cellName === searchName) {
      rowIndex = r + 1;  // Sheets are 1-indexed, plus we skipped header
      Logger.log("Found client '" + data.clientName + "' at row " + rowIndex);
      break;
    }
  }

  if (rowIndex === -1) {
    // Try partial match as fallback
    for (var r = 1; r < names.length; r++) {
      var cellName = names[r][0].toString().trim().toLowerCase();
      if (cellName.indexOf(searchName) !== -1 || searchName.indexOf(cellName) !== -1) {
        rowIndex = r + 1;
        Logger.log("Found client '" + data.clientName + "' via partial match at row " + rowIndex);
        break;
      }
    }
  }

  if (rowIndex === -1) {
    Logger.log("Client not found. Looking for: '" + data.clientName + "'");
    return {
      status: "error",
      message: "Client not found: " + data.clientName
    };
  }

  // Update the cell
  var cell = sheet.getRange(rowIndex, colIndex);
  var oldValue = cell.getValue();
  cell.setValue(data.value);

  Logger.log("Updated cell (" + rowIndex + ", " + colIndex + "): '" + oldValue + "' -> '" + data.value + "'");

  return {
    status: "ok",
    message: "Updated successfully",
    row: rowIndex,
    col: colIndex,
    oldValue: oldValue.toString(),
    newValue: data.value
  };
}

/**
 * Helper to create JSON response
 */
function jsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
