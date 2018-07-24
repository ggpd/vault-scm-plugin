pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                echo 'mvn clean install'
            }
        }
        stage('Store') {
            steps {
                archiveArtifacts 'target/vault-scm-plugin.hpi'
            }
        }
    }
}