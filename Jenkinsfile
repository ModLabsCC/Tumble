pipeline {
    agent any
    tools { jdk 'jdk25' }
    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    stages {
        stage('Inspect test environment') {
            steps {
                sh 'id; cat /etc/os-release; command -v Xvfb || true; command -v docker || true; command -v apt-get || true; command -v xauth || true; ls /usr/lib/x86_64-linux-gnu/dri/swrast_dri.so || true'
            }
        }
        stage('Build and GameTests') {
            steps {
                sh 'chmod +x gradlew'
                sh './gradlew clean build -PacceptMinecraftEula=true --no-daemon --stacktrace'
            }
        }
        stage('Client GameTests') {
            steps {
                sh 'xvfb-run -a ./gradlew runClientGameTest -PacceptMinecraftEula=true --no-daemon --stacktrace'
            }
        }
        stage('Packaged server GameTests') {
            steps {
                sh './gradlew runProductionGameTest -PacceptMinecraftEula=true --no-daemon --stacktrace'
            }
        }
        stage('Physics benchmark') {
            steps {
                sh './gradlew :physics-core:benchmark --no-daemon --stacktrace'
            }
        }
    }
    post {
        success { archiveArtifacts artifacts: 'build/libs/*.jar', excludes: 'build/libs/*-gametest.jar', fingerprint: true }
        always {
            junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
            archiveArtifacts artifacts: 'build/run/gameTest/logs/*.log,build/run/clientGameTest/logs/*.log,build/run/productionGameTest/logs/*.log', allowEmptyArchive: true
        }
    }
}
