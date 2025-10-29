import java.util.*;
import java.io.*;

public class AADS {

    // Global timing variables
    private static long startTime;
    private static final long TIME_LIMIT_MS = 115000; // 115 seconds

    // ==================== Core Data Structures ====================

    static class ViewPoint {
        private final String id;
        private final double x, y, z;
        private final boolean isMandatory;
        private final Map<String, Double> precisionMap; // angle_id -> precision

        public ViewPoint(String id, double x, double y, double z, boolean isMandatory) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.isMandatory = isMandatory;
            this.precisionMap = new HashMap<>();
        }

        public String getId() { return id; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public boolean isMandatory() { return isMandatory; }
        public Map<String, Double> getPrecisionMap() { return precisionMap; }

        public void addPrecision(String angleId, double precision) {
            precisionMap.put(angleId, precision);
        }

        public double getPrecision(String angleId) {
            return precisionMap.getOrDefault(angleId, 0.0);
        }

        public double distanceTo(ViewPoint other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            double dz = this.z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ViewPoint)) return false;
            return id.equals(((ViewPoint) obj).id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    static class SamplePoint {
        private final String id;
        private final double x, y, z;
        private final List<String[]> coveringPairs; // [viewpoint_id, angle_id]
        private boolean isCovered;

        public SamplePoint(String id, double x, double y, double z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.coveringPairs = new ArrayList<>();
            this.isCovered = false;
        }

        public String getId() { return id; }
        public List<String[]> getCoveringPairs() { return coveringPairs; }
        public boolean isCovered() { return isCovered; }
        public void setCovered(boolean covered) { this.isCovered = covered; }

        public void addCoveringPair(String viewpointId, String angleId) {
            coveringPairs.add(new String[]{viewpointId, angleId});
        }
    }

    static class Solution {
        private final List<ViewPoint> tour;
        private final Map<ViewPoint, Set<String>> selectedAngles; // viewpoint -> angle_ids
        private double totalDistance;
        private double totalPrecision;
        private double objectiveValue;

        public Solution() {
            this.tour = new ArrayList<>();
            this.selectedAngles = new HashMap<>();
            this.totalDistance = 0.0;
            this.totalPrecision = 0.0;
            this.objectiveValue = 0.0;
        }

        public List<ViewPoint> getTour() { return tour; }
        public Map<ViewPoint, Set<String>> getSelectedAngles() { return selectedAngles; }
        public double getTotalDistance() { return totalDistance; }
        public double getTotalPrecision() { return totalPrecision; }
        public double getObjectiveValue() { return objectiveValue; }

        public void addViewPoint(ViewPoint vp) {
            tour.add(vp);
            selectedAngles.putIfAbsent(vp, new HashSet<>());
        }

        public void addAngle(ViewPoint vp, String angleId) {
            selectedAngles.computeIfAbsent(vp, k -> new HashSet<>()).add(angleId);
        }

        public void setTotalDistance(double d) { this.totalDistance = d; }
        public void setTotalPrecision(double p) { this.totalPrecision = p; }
        public void setObjectiveValue(double v) { this.objectiveValue = v; }
    }

    // ==================== JSON Parsing ====================

    private static Map<String, ViewPoint> parseViewPoints(String input) {
        Map<String, ViewPoint> viewPoints = new HashMap<>();
        try {
            int vpStart = input.indexOf("\"viewpoints\"");
            if (vpStart == -1) return viewPoints;

            int arrayStart = input.indexOf("[", vpStart);
            int arrayEnd = findMatchingBracket(input, arrayStart);
            String vpArray = input.substring(arrayStart, arrayEnd + 1);

            int pos = 1;
            while (pos < vpArray.length()) {
                int objStart = vpArray.indexOf("{", pos);
                if (objStart == -1) break;

                int objEnd = findMatchingBrace(vpArray, objStart);
                String vpObj = vpArray.substring(objStart, objEnd + 1);

                // Parse viewpoint
                String id = extractStringValue(vpObj, "\"id\"");
                boolean mandatory = vpObj.contains("\"is_mandatory\": true");

                // Parse coordinates
                int coordStart = vpObj.indexOf("\"coordinates\"");
                int coordObjStart = vpObj.indexOf("{", coordStart);
                int coordObjEnd = findMatchingBrace(vpObj, coordObjStart);
                String coordObj = vpObj.substring(coordObjStart, coordObjEnd + 1);

                double x = extractDouble(coordObj, "\"x\"");
                double y = extractDouble(coordObj, "\"y\"");
                double z = extractDouble(coordObj, "\"z\"");

                ViewPoint vp = new ViewPoint(id, x, y, z, mandatory);

                // Parse precision map
                int precStart = vpObj.indexOf("\"precision\"");
                if (precStart != -1) {
                    int precObjStart = vpObj.indexOf("{", precStart);
                    int precObjEnd = findMatchingBrace(vpObj, precObjStart);
                    String precObj = vpObj.substring(precObjStart, precObjEnd + 1);

                    // Extract all angle:precision pairs
                    String[] parts = precObj.split(",");
                    for (String part : parts) {
                        if (part.contains(":")) {
                            String angleId = extractStringValue(part, "\"");
                            if (!angleId.isEmpty() && angleId.startsWith("a")) {
                                double precision = extractDoubleFromPair(part);
                                vp.addPrecision(angleId, precision);
                            }
                        }
                    }
                }

                viewPoints.put(id, vp);
                pos = objEnd + 1;
            }
        } catch (Exception e) {
            System.err.println("Error parsing viewpoints: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.err.println("Parsed " + viewPoints.size() + " viewpoints");
        return viewPoints;
    }

    private static Map<String, SamplePoint> parseSamplePoints(String json) {
        Map<String, SamplePoint> samplePoints = new HashMap<>();
        try {
            int spStart = json.indexOf("\"sample_points\"");
            if (spStart == -1) return samplePoints;

            int arrayStart = json.indexOf("[", spStart);
            int arrayEnd = findMatchingBracket(json, arrayStart);
            String spSection = json.substring(arrayStart, arrayEnd + 1);

            int pos = 1;
            while (pos < spSection.length()) {
                int objStart = spSection.indexOf("{", pos);
                if (objStart == -1) break;

                int objEnd = findMatchingBrace(spSection, objStart);
                String spObj = spSection.substring(objStart, objEnd + 1);

                String id = extractStringValue(spObj, "\"id\"");

                // Parse coordinates
                int coordStart = spObj.indexOf("\"coordinates\"");
                int coordObjStart = spObj.indexOf("{", coordStart);
                int coordObjEnd = findMatchingBrace(spObj, coordObjStart);
                String coordObj = spObj.substring(coordObjStart, coordObjEnd + 1);

                double x = extractDouble(coordObj, "\"x\"");
                double y = extractDouble(coordObj, "\"y\"");
                double z = extractDouble(coordObj, "\"z\"");

                SamplePoint sp = new SamplePoint(id, x, y, z);

                // Parse covering pairs
                int cpStart = spObj.indexOf("\"covering_pairs\"");
                if (cpStart != -1) {
                    int cpArrayStart = spObj.indexOf("[", cpStart);
                    int cpArrayEnd = findMatchingBracket(spObj, cpArrayStart);
                    String cpArray = spObj.substring(cpArrayStart, cpArrayEnd + 1);

                    int cpPos = 1;
                    while (cpPos < cpArray.length()) {
                        int pairStart = cpArray.indexOf("[", cpPos);
                        if (pairStart == -1) break;

                        int pairEnd = cpArray.indexOf("]", pairStart);
                        String pair = cpArray.substring(pairStart + 1, pairEnd);

                        String[] parts = pair.split(",");
                        if (parts.length >= 2) {
                            String vpId = parts[0].replace("\"", "").trim();
                            String angleId = parts[1].replace("\"", "").trim();
                            sp.addCoveringPair(vpId, angleId);
                        }

                        cpPos = pairEnd + 1;
                    }
                }

                samplePoints.put(id, sp);
                pos = objEnd + 1;
            }
        } catch (Exception e) {
            System.err.println("Error parsing sample points: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.err.println("Parsed " + samplePoints.size() + " sample points");
        return samplePoints;
    }

    private static int[][] parseCollisionMatrix(String input) {
        try {
            int cmStart = input.indexOf("\"collision_matrix\"");
            if (cmStart == -1) return new int[0][0];

            int arrayStart = input.indexOf("[", cmStart);
            int arrayEnd = findMatchingBracket(input, arrayStart);
            String cmArray = input.substring(arrayStart + 1, arrayEnd);

            // Count rows
            List<int[]> rows = new ArrayList<>();
            int pos = 0;

            while (pos < cmArray.length()) {
                int rowStart = cmArray.indexOf("[", pos);
                if (rowStart == -1) break;

                int rowEnd = cmArray.indexOf("]", rowStart);
                String rowStr = cmArray.substring(rowStart + 1, rowEnd);

                String[] values = rowStr.split(",");
                int[] row = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    row[i] = Integer.parseInt(values[i].trim());
                }
                rows.add(row);

                pos = rowEnd + 1;
            }

            int[][] matrix = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                matrix[i] = rows.get(i);
            }

            System.err.println("Parsed collision matrix: " + matrix.length + "x" +
                    (matrix.length > 0 ? matrix[0].length : 0));
            return matrix;
        } catch (Exception e) {
            System.err.println("Error parsing collision matrix: " + e.getMessage());
            return new int[0][0];
        }
    }

    // Helper methods
    private static int findMatchingBracket(String str, int start) {
        int count = 1;
        for (int i = start + 1; i < str.length(); i++) {
            if (str.charAt(i) == '[') count++;
            else if (str.charAt(i) == ']') {
                count--;
                if (count == 0) return i;
            }
        }
        return str.length() - 1;
    }

    private static int findMatchingBrace(String str, int start) {
        int count = 1;
        for (int i = start + 1; i < str.length(); i++) {
            if (str.charAt(i) == '{') count++;
            else if (str.charAt(i) == '}') {
                count--;
                if (count == 0) return i;
            }
        }
        return str.length() - 1;
    }

    private static String extractStringValue(String json, String key) {
        try {
            int keyPos = json.indexOf(key);
            if (keyPos == -1) return "";

            int colonPos = json.indexOf(":", keyPos);
            int quoteStart = json.indexOf("\"", colonPos);
            int quoteEnd = json.indexOf("\"", quoteStart + 1);

            return json.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return "";
        }
    }

    private static double extractDouble(String json, String key) {
        try {
            int keyPos = json.indexOf(key);
            if (keyPos == -1) return 0.0;

            int colonPos = json.indexOf(":", keyPos);
            int commaPos = json.indexOf(",", colonPos);
            int bracePos = json.indexOf("}", colonPos);

            int endPos = (commaPos != -1 && commaPos < bracePos) ? commaPos : bracePos;
            String value = json.substring(colonPos + 1, endPos).trim();

            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double extractDoubleFromPair(String pair) {
        try {
            int colonPos = pair.indexOf(":");
            if (colonPos == -1) return 0.0;

            String value = pair.substring(colonPos + 1).trim();
            value = value.replace("}", "").replace("]", "").trim();

            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ==================== Algorithm Implementation ====================

    private static void checkTimeLimit(String phase) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > TIME_LIMIT_MS) {
            System.err.println("Time limit at " + phase + ": " + elapsed / 1000.0 + "s");
            System.exit(1);
        }
    }

    private static Solution solveUAVProblem(String input) {
        checkTimeLimit("Start solve");

        // Phase 1: Parse
        System.err.println("Phase 1: Parsing...");
        Map<String, ViewPoint> viewPoints = parseViewPoints(input);
        Map<String, SamplePoint> samplePoints = parseSamplePoints(input);
        int[][] collisionMatrix = parseCollisionMatrix(input);
        checkTimeLimit("After parsing");

        // Build viewpoint index for validation
        List<ViewPoint> vpList = new ArrayList<>(viewPoints.values());
        Map<String, Integer> vpIndex = new HashMap<>();
        for (int i = 0; i < vpList.size(); i++) {
            vpIndex.put(vpList.get(i).getId(), i);
        }

        // Phase 2: Greedy construction
        System.err.println("Phase 2: Greedy construction...");
        Solution solution = greedyConstruction(viewPoints, samplePoints, collisionMatrix);
        checkTimeLimit("After greedy");

        // Phase 3: Calculate metrics
        System.err.println("Phase 3: Calculating metrics...");
        calculateMetrics(solution, viewPoints);

        // Phase 4: Validate solution
        System.err.println("Phase 4: Validating solution...");
        boolean isValid = validateSolution(solution, viewPoints, samplePoints, vpIndex, collisionMatrix);
        if (!isValid) {
            System.err.println("WARNING: Solution does not satisfy all constraints!");
        }
        checkTimeLimit("After validation");

        System.err.println("Solution: " + solution.getTour().size() + " viewpoints, " +
                "distance=" + String.format("%.2f", solution.getTotalDistance()) +
                ", precision=" + String.format("%.2f", solution.getTotalPrecision()));

        return solution;
    }

    private static Solution greedyConstruction(Map<String, ViewPoint> viewPoints,
                                               Map<String, SamplePoint> samplePoints,
                                               int[][] collisionMatrix) {
        Solution solution = new Solution();
        Set<String> coveredSamples = new HashSet<>();
        Map<String, Integer> coverageCount = new HashMap<>(); // sample_id -> count

        // Index viewpoints
        List<ViewPoint> vpList = new ArrayList<>(viewPoints.values());
        Map<String, Integer> vpIndex = new HashMap<>();
        for (int i = 0; i < vpList.size(); i++) {
            vpIndex.put(vpList.get(i).getId(), i);
        }

        // Find and add mandatory viewpoint as starting point
        ViewPoint mandatoryVP = null;
        for (ViewPoint vp : vpList) {
            if (vp.isMandatory()) {
                mandatoryVP = vp;
                solution.addViewPoint(vp);
                System.err.println("Added mandatory viewpoint as tour start: " + vp.getId());
                break;
            }
        }

        if (mandatoryVP == null) {
            System.err.println("ERROR: No mandatory viewpoint found!");
            return solution;
        }

        // Greedy: select viewpoints that cover most uncovered samples
        // and can be connected to the tour via collision matrix
        while (coveredSamples.size() < samplePoints.size() && solution.getTour().size() < vpList.size()) {
            checkTimeLimit("During greedy");

            ViewPoint bestVP = null;
            int maxNewCoverage = 0;
            int bestInsertPosition = -1;

            List<ViewPoint> currentTour = solution.getTour();

            for (ViewPoint vp : vpList) {
                if (currentTour.contains(vp)) continue;

                // Calculate coverage benefit
                int newCoverage = 0;
                for (SamplePoint sp : samplePoints.values()) {
                    int currentCoverage = coverageCount.getOrDefault(sp.getId(), 0);
                    if (currentCoverage >= 3) continue; // Already covered 3+ times

                    // Check if this viewpoint can cover this sample
                    for (String[] pair : sp.getCoveringPairs()) {
                        if (pair[0].equals(vp.getId())) {
                            newCoverage++;
                            break;
                        }
                    }
                }

                // Only consider viewpoints that provide coverage benefit
                if (newCoverage == 0) continue;

                // Check if this viewpoint can be connected to the tour
                // Find the best position to insert this viewpoint
                Integer vpIdx = vpIndex.get(vp.getId());
                if (vpIdx == null) continue;

                int bestPos = -1;
                double minDistanceIncrease = Double.MAX_VALUE;

                // Try inserting at each position in the tour
                // NOTE: Position 0 is reserved for the mandatory viewpoint, so we start from position 1
                int startPos = (currentTour.size() == 1) ? 1 : 1;
                int endPos = currentTour.size();

                for (int pos = startPos; pos <= endPos; pos++) {
                    int prevIdx = -1;
                    int nextIdx = -1;

                    if (currentTour.size() == 1) {
                        // Special case: only mandatory viewpoint in tour
                        // Check if we can go from mandatory -> vp -> mandatory
                        prevIdx = vpIndex.get(currentTour.get(0).getId());
                        nextIdx = prevIdx;
                    } else if (pos == currentTour.size()) {
                        // Insert at end: check last -> vp and vp -> mandatory (position 0)
                        prevIdx = vpIndex.get(currentTour.get(currentTour.size() - 1).getId());
                        nextIdx = vpIndex.get(currentTour.get(0).getId());
                    } else {
                        // Insert in middle: check prev -> vp and vp -> next
                        prevIdx = vpIndex.get(currentTour.get(pos - 1).getId());
                        nextIdx = vpIndex.get(currentTour.get(pos).getId());
                    }

                    // Check if connections are valid in collision matrix
                    if (prevIdx >= 0 && nextIdx >= 0 &&
                        collisionMatrix[prevIdx][vpIdx] == 1 &&
                        collisionMatrix[vpIdx][nextIdx] == 1) {

                        // Calculate distance increase
                        double distIncrease = currentTour.get(pos == 0 ? currentTour.size() - 1 : pos - 1).distanceTo(vp) +
                                             vp.distanceTo(currentTour.get(pos == currentTour.size() ? 0 : pos));

                        if (pos > 0 && pos < currentTour.size()) {
                            distIncrease -= currentTour.get(pos - 1).distanceTo(currentTour.get(pos));
                        }

                        if (distIncrease < minDistanceIncrease) {
                            minDistanceIncrease = distIncrease;
                            bestPos = pos;
                        }
                    }
                }

                // If we found a valid insertion position and this viewpoint has better coverage
                if (bestPos >= 0 && newCoverage > maxNewCoverage) {
                    maxNewCoverage = newCoverage;
                    bestVP = vp;
                    bestInsertPosition = bestPos;
                }
            }

            if (bestVP == null || maxNewCoverage == 0) {
                System.err.println("No more connectable viewpoints with coverage benefit");
                break;
            }

            // Insert viewpoint at the best position
            // Ensure we never insert at position 0 (reserved for mandatory viewpoint)
            if (bestInsertPosition > 0 && bestInsertPosition < currentTour.size()) {
                currentTour.add(bestInsertPosition, bestVP);
            } else {
                // Insert at the end
                currentTour.add(bestVP);
            }
            solution.getSelectedAngles().putIfAbsent(bestVP, new HashSet<>());

            // Update coverage
            for (SamplePoint sp : samplePoints.values()) {
                for (String[] pair : sp.getCoveringPairs()) {
                    if (pair[0].equals(bestVP.getId())) {
                        String angleId = pair[1];
                        solution.addAngle(bestVP, angleId);

                        coverageCount.put(sp.getId(),
                                coverageCount.getOrDefault(sp.getId(), 0) + 1);
                        if (coverageCount.get(sp.getId()) >= 3) {
                            coveredSamples.add(sp.getId());
                        }
                        break;
                    }
                }
            }
        }

        System.err.println("Greedy: " + solution.getTour().size() + " viewpoints, " +
                coveredSamples.size() + "/" + samplePoints.size() + " samples fully covered");

        return solution;
    }

    private static void calculateMetrics(Solution solution, Map<String, ViewPoint> viewPoints) {
        // Calculate total distance
        double totalDist = 0.0;
        List<ViewPoint> tour = solution.getTour();
        for (int i = 0; i < tour.size() - 1; i++) {
            totalDist += tour.get(i).distanceTo(tour.get(i + 1));
        }
        // Close the loop
        if (tour.size() > 1) {
            totalDist += tour.get(tour.size() - 1).distanceTo(tour.get(0));
        }
        solution.setTotalDistance(totalDist);

        // Calculate total precision
        double totalPrec = 0.0;
        for (ViewPoint vp : tour) {
            Set<String> angles = solution.getSelectedAngles().get(vp);
            if (angles != null) {
                for (String angleId : angles) {
                    totalPrec += vp.getPrecision(angleId);
                }
            }
        }
        solution.setTotalPrecision(totalPrec);

        // Objective: distance - precision
        solution.setObjectiveValue(totalDist - totalPrec);
    }

    // ==================== Validation Functions ====================

    /**
     * Validates that the tour forms a continuous, connected path.
     * Ensures consecutive viewpoints have collision_matrix[i][j] == 1.
     * Returns true if tour is valid, false otherwise.
     */
    private static boolean validateTourConnectivity(Solution solution,
                                                     Map<String, Integer> vpIndex,
                                                     int[][] collisionMatrix) {
        List<ViewPoint> tour = solution.getTour();

        if (tour.isEmpty()) {
            System.err.println("VALIDATION ERROR: Empty tour");
            return false;
        }

        if (tour.size() == 1) {
            // Single viewpoint tour is valid if it's the mandatory one
            return tour.get(0).isMandatory();
        }

        // Check connectivity between consecutive viewpoints
        for (int i = 0; i < tour.size() - 1; i++) {
            ViewPoint current = tour.get(i);
            ViewPoint next = tour.get(i + 1);

            Integer currentIdx = vpIndex.get(current.getId());
            Integer nextIdx = vpIndex.get(next.getId());

            if (currentIdx == null || nextIdx == null) {
                System.err.println("VALIDATION ERROR: Viewpoint not in index - " +
                    current.getId() + " or " + next.getId());
                return false;
            }

            // Check if connection is allowed
            if (collisionMatrix[currentIdx][nextIdx] != 1) {
                System.err.println("VALIDATION ERROR: Invalid connection from " +
                    current.getId() + " (idx " + currentIdx + ") to " +
                    next.getId() + " (idx " + nextIdx + ") - collision_matrix[" +
                    currentIdx + "][" + nextIdx + "] = " +
                    collisionMatrix[currentIdx][nextIdx]);
                return false;
            }
        }

        // Check closing edge (from last back to first)
        if (tour.size() > 1) {
            ViewPoint last = tour.get(tour.size() - 1);
            ViewPoint first = tour.get(0);

            Integer lastIdx = vpIndex.get(last.getId());
            Integer firstIdx = vpIndex.get(first.getId());

            if (lastIdx == null || firstIdx == null) {
                System.err.println("VALIDATION ERROR: Viewpoint not in index (closing edge)");
                return false;
            }

            if (collisionMatrix[lastIdx][firstIdx] != 1) {
                System.err.println("VALIDATION ERROR: Cannot close tour - invalid connection from " +
                    last.getId() + " (idx " + lastIdx + ") back to " +
                    first.getId() + " (idx " + firstIdx + ") - collision_matrix[" +
                    lastIdx + "][" + firstIdx + "] = " +
                    collisionMatrix[lastIdx][firstIdx]);
                return false;
            }
        }

        System.err.println("Tour connectivity: VALID");
        return true;
    }

    /**
     * Validates coverage constraints:
     * 1. Each sample point must be covered at least 3 times from different viewpoint-direction pairs
     * 2. All sample points must be covered by at least one viewpoint-direction pair
     */
    private static boolean validateCoverageConstraints(Solution solution,
                                                        Map<String, SamplePoint> samplePoints) {
        Map<String, Integer> coverageCount = new HashMap<>();
        Map<String, Set<String>> coveringViewpoints = new HashMap<>(); // sample_id -> set of viewpoint_ids

        List<ViewPoint> tour = solution.getTour();
        Map<ViewPoint, Set<String>> selectedAngles = solution.getSelectedAngles();

        // Build set of selected viewpoint-angle pairs
        Set<String> selectedPairs = new HashSet<>();
        for (ViewPoint vp : tour) {
            Set<String> angles = selectedAngles.get(vp);
            if (angles != null) {
                for (String angle : angles) {
                    selectedPairs.add(vp.getId() + ":" + angle);
                }
            }
        }

        // Count coverage for each sample point
        for (SamplePoint sp : samplePoints.values()) {
            int count = 0;
            Set<String> coveringVPs = new HashSet<>();

            for (String[] pair : sp.getCoveringPairs()) {
                String vpId = pair[0];
                String angleId = pair[1];
                String pairKey = vpId + ":" + angleId;

                if (selectedPairs.contains(pairKey)) {
                    count++;
                    coveringVPs.add(vpId);
                }
            }

            coverageCount.put(sp.getId(), count);
            coveringViewpoints.put(sp.getId(), coveringVPs);
        }

        // Validate constraints
        boolean allValid = true;
        int uncoveredCount = 0;
        int underCoveredCount = 0;

        for (SamplePoint sp : samplePoints.values()) {
            int count = coverageCount.getOrDefault(sp.getId(), 0);

            if (count == 0) {
                uncoveredCount++;
                allValid = false;
            } else if (count < 3) {
                underCoveredCount++;
                allValid = false;
            }
        }

        if (uncoveredCount > 0) {
            System.err.println("VALIDATION ERROR: " + uncoveredCount +
                " sample points are not covered at all");
        }

        if (underCoveredCount > 0) {
            System.err.println("VALIDATION ERROR: " + underCoveredCount +
                " sample points are covered less than 3 times");
        }

        if (allValid) {
            System.err.println("Coverage constraints: VALID (all " + samplePoints.size() +
                " samples covered 3+ times)");
        } else {
            System.err.println("Coverage constraints: INVALID (" +
                (samplePoints.size() - uncoveredCount - underCoveredCount) + "/" +
                samplePoints.size() + " samples properly covered)");
        }

        return allValid;
    }

    /**
     * Validates that the tour starts and ends at the mandatory viewpoint.
     */
    private static boolean validateMandatoryStartEnd(Solution solution) {
        List<ViewPoint> tour = solution.getTour();

        if (tour.isEmpty()) {
            System.err.println("VALIDATION ERROR: Empty tour");
            return false;
        }

        ViewPoint first = tour.get(0);

        if (!first.isMandatory()) {
            System.err.println("VALIDATION ERROR: Tour does not start at mandatory viewpoint. " +
                "First viewpoint: " + first.getId());
            return false;
        }

        // Since the tour is a cycle, starting at mandatory means we also end there
        System.err.println("Mandatory start/end: VALID (tour starts at " + first.getId() + ")");
        return true;
    }

    /**
     * Comprehensive validation of the solution.
     */
    private static boolean validateSolution(Solution solution,
                                             Map<String, ViewPoint> viewPoints,
                                             Map<String, SamplePoint> samplePoints,
                                             Map<String, Integer> vpIndex,
                                             int[][] collisionMatrix) {
        System.err.println("\n=== Solution Validation ===");

        boolean mandatoryValid = validateMandatoryStartEnd(solution);
        boolean connectivityValid = validateTourConnectivity(solution, vpIndex, collisionMatrix);
        boolean coverageValid = validateCoverageConstraints(solution, samplePoints);

        boolean allValid = mandatoryValid && connectivityValid && coverageValid;

        if (allValid) {
            System.err.println("=== ALL VALIDATIONS PASSED ===\n");
        } else {
            System.err.println("=== VALIDATION FAILED ===\n");
        }

        return allValid;
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        startTime = System.currentTimeMillis();

        try {
            System.err.println("=== AADS Starting ===");

            // Read input
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder inputBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                inputBuilder.append(line);
                checkTimeLimit("Reading input");
            }

            String input = inputBuilder.toString();
            System.err.println("Input: " + input.length() + " characters");

            // Solve
            Solution solution = solveUAVProblem(input);

            // Output
            outputSolution(solution);

            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("=== Completed in " + elapsed / 1000.0 + "s ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void outputSolution(Solution solution) {
        System.out.println("{");
        System.out.println("  \"metadata\": {");
        System.out.println("    \"num_viewpoints\": " + solution.getTour().size() + ",");
        System.out.println("    \"objective\": {");
        System.out.printf("      \"distance\": %.2f,%n", solution.getTotalDistance());
        System.out.printf("      \"precision\": %.2f%n", solution.getTotalPrecision());
        System.out.println("    }");
        System.out.println("  },");
        System.out.println("  \"sequence\": [");

        List<ViewPoint> tour = solution.getTour();
        for (int i = 0; i < tour.size(); i++) {
            ViewPoint vp = tour.get(i);
            System.out.println("    {");
            System.out.println("      \"id\": \"" + vp.getId() + "\",");
            System.out.println("      \"angles\": [");

            Set<String> angles = solution.getSelectedAngles().get(vp);
            if (angles != null && !angles.isEmpty()) {
                List<String> angleList = new ArrayList<>(angles);
                for (int j = 0; j < angleList.size(); j++) {
                    System.out.print("        \"" + angleList.get(j) + "\"");
                    if (j < angleList.size() - 1) System.out.print(",");
                    System.out.println();
                }
            }

            System.out.println("      ]");
            System.out.print("    }");
            if (i < tour.size() - 1) System.out.print(",");
            System.out.println();
        }

        System.out.println("  ]");
        System.out.println("}");
    }
}
