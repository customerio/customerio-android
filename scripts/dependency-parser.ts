#!/usr/bin/env node
/**
 * Simple parser that handles patch/minor updates only
 */

import { readFileSync, writeFileSync } from 'fs';

interface DependencyUpdate {
    name: string;
    current: string;
    new: string;
    type: 'patch' | 'minor';
}

interface MajorUpdate {
    name: string;
    current: string;
    new: string;
}

class SimpleDependencyParser {
    
    parseVersion(version: string): { major: number; minor: number; patch: number } {
        const parts = version.split('.').map(part => parseInt(part.replace(/[^0-9]/g, ''), 10));
        return {
            major: parts[0] || 0,
            minor: parts[1] || 0,
            patch: parts[2] || 0
        };
    }

    getUpdateType(current: string, available: string): 'patch' | 'minor' | 'major' | null {
        const currentVer = this.parseVersion(current);
        const availableVer = this.parseVersion(available);

        // Skip downgrades
        if (availableVer.major < currentVer.major ||
            (availableVer.major === currentVer.major && availableVer.minor < currentVer.minor) ||
            (availableVer.major === currentVer.major && availableVer.minor === currentVer.minor && availableVer.patch <= currentVer.patch)) {
            return null;
        }

        if (currentVer.major !== availableVer.major) {
            return 'major';
        } else if (currentVer.minor !== availableVer.minor) {
            return 'minor';
        } else {
            return 'patch';
        }
    }

    parseReport(): { updates: DependencyUpdate[]; majorUpdates: MajorUpdate[] } {
        const reportPath = 'build/dependencyUpdates/report.json';
        const report = JSON.parse(readFileSync(reportPath, 'utf-8'));
        
        const updates: DependencyUpdate[] = [];
        const majorUpdates: MajorUpdate[] = [];
        
        const dependencies = report.outdated?.dependencies || [];
        
        for (const dep of dependencies) {
            const name = `${dep.group}:${dep.name}`;
            const current = dep.version;
            const available = dep.available?.release || dep.available?.milestone;
            
            if (!available) continue;
            
            const updateType = this.getUpdateType(current, available);
            
            if (updateType === 'patch' || updateType === 'minor') {
                updates.push({
                    name,
                    current,
                    new: available,
                    type: updateType
                });
            } else if (updateType === 'major') {
                majorUpdates.push({
                    name,
                    current,
                    new: available
                });
            }
        }
        
        return { updates, majorUpdates };
    }

    updateVersionsFile(updates: DependencyUpdate[]): void {
        const versionsPath = 'buildSrc/src/main/kotlin/io.customer/android/Versions.kt';
        let content = readFileSync(versionsPath, 'utf-8');
        
        for (const update of updates) {
            // Simple regex replacement for version constants
            // This assumes format: internal const val SOME_NAME = "1.2.3"
            const regex = new RegExp(`(internal const val [A-Z_]+ = ")${update.current.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(")`, 'g');
            content = content.replace(regex, `$1${update.new}$2`);
        }
        
        writeFileSync(versionsPath, content);
    }

    generateSummary(updates: DependencyUpdate[]): string {
        if (updates.length === 0) {
            return 'No updates applied.';
        }
        
        return updates.map(update => 
            `- **${update.name}**: \`${update.current}\` → \`${update.new}\` (${update.type})`
        ).join('\n');
    }

    generateMajorUpdatesSummary(majorUpdates: MajorUpdate[]): string {
        if (majorUpdates.length === 0) {
            return 'No major updates available.';
        }
        
        return '⚠️ **Major updates require manual review:**\n' + 
               majorUpdates.map(update => 
                   `- **${update.name}**: \`${update.current}\` → \`${update.new}\` (major - breaking changes possible)`
               ).join('\n');
    }

    setGitHubOutput(key: string, value: string): void {
        const outputFile = process.env.GITHUB_OUTPUT;
        if (outputFile) {
            writeFileSync(outputFile, `${key}=${value}\n`, { flag: 'a' });
        }
    }
}

function main(): void {
    const parser = new SimpleDependencyParser();
    const { updates, majorUpdates } = parser.parseReport();
    
    console.log(`Found ${updates.length} patch/minor updates`);
    console.log(`Found ${majorUpdates.length} major updates (skipped)`);
    
    if (updates.length > 0) {
        parser.updateVersionsFile(updates);
        console.log('✅ Applied updates to Versions.kt');
        
        parser.setGitHubOutput('has_updates', 'true');
        parser.setGitHubOutput('summary', parser.generateSummary(updates));
    } else {
        parser.setGitHubOutput('has_updates', 'false');
        parser.setGitHubOutput('summary', 'No updates applied.');
    }
    
    parser.setGitHubOutput('major_updates', parser.generateMajorUpdatesSummary(majorUpdates));
}

// Run main function if this is the entry point
main(); 