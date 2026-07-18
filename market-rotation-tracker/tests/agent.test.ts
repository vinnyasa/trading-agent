import { MarketRotationAgent } from '../src/agent';

describe('MarketRotationAgent', () => {
    let agent: MarketRotationAgent;

    beforeEach(() => {
        agent = new MarketRotationAgent();
    });

    test('should start tracking', () => {
        agent.startTracking();
        expect(agent.isTracking).toBe(true);
    });

    test('should stop tracking', () => {
        agent.startTracking();
        agent.stopTracking();
        expect(agent.isTracking).toBe(false);
    });

    test('should get current rotation', () => {
        agent.startTracking();
        const rotation = agent.getCurrentRotation();
        expect(rotation).toBeDefined();
    });
});