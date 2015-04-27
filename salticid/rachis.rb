require 'fileutils'

role :rachis do
  task :mono do
    sudo do
      exec! 'sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 3FA7E0328081BFF6A14DA29AA6A19B38D3D831EF ', echo: true
      exec! 'echo "deb http://download.mono-project.com/repo/debian wheezy main" | sudo tee /etc/apt/sources.list.d/mono-xamarin.list', echo: true
      exec! 'sudo apt-get update', echo: true
      exec! 'sudo apt-get install -y mono-complete', echo: true
    end
  end

  task :clone do
    sudo do
      cd '/opt/'
      unless dir? :Rachis
        git :clone, 'https://github.com/ml054/Rachis.git', echo: true
      end
      unless dir? :ravendb
        git :clone, 'http://10.0.3.1/ravendb/', echo: true
      end
    end
  end

  task :build do
    sudo do
      cd '/opt/Rachis/'
      git :pull, echo:true
      cd '/opt/ravendb'
      git :pull, echo:true
      cd '/opt/Rachis/'
      exec! 'xbuild', echo: true
    end
  end
end
