import { RotationTrackerService } from '../src/services/rotationTracker';

describe('RotationTrackerService', () => {
    let rotationTrackerService: RotationTrackerService;

    beforeEach(() => {
        rotationTrackerService = new RotationTrackerService();
    });

    test('should track rotation correctly', () => {
        // Arrange
        const marketData = { /* mock market data */ };
        
        // Act
        rotationTrackerService.trackRotation(marketData);
        
        // Assert
        const currentRotation = rotationTrackerService.getRotationHistory();
        expect(currentRotation).toContainEqual(expect.objectContaining(marketData));
    });

    test('should retrieve rotation history', () => {
        // Arrange
        const marketData1 = { /* mock market data 1 */ };
        const marketData2 = { /* mock market data 2 */ };
        rotationTrackerService.trackRotation(marketData1);
        rotationTrackerService.trackRotation(marketData2);
        
        // Act
        const history = rotationTrackerService.getRotationHistory();
        
        // Assert
        expect(history).toHaveLength(2);
        expect(history).toContainEqual(expect.objectContaining(marketData1));
        expect(history).toContainEqual(expect.objectContaining(marketData2));
    });
});