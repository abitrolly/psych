# -*- ruby -*-

require 'rubygems'
require 'hoe'

def java?
  RUBY_PLATFORM =~ /java/
end

class Hoe
  remove_const :RUBY_FLAGS
  flags = "-I#{%w(lib ext bin test).join(File::PATH_SEPARATOR)}"
  flags = "--1.9 " + flags if java?
  RUBY_FLAGS = flags
end

gem 'rake-compiler', '>= 0.4.1'
require "rake/extensiontask"

Hoe.plugin :doofus, :git, :gemspec, :isolate

$hoe = Hoe.spec 'psych' do
  developer 'Aaron Patterson', 'aaron@tenderlovemaking.com'

  self.extra_rdoc_files  = Dir['*.rdoc']
  self.history_file      = 'CHANGELOG.rdoc'
  self.readme_file       = 'README.rdoc'
  self.testlib           = :minitest

  extra_dev_deps << ['rake-compiler', '>= 0.4.1']

  self.spec_extras = {
    :extensions            => ["ext/psych/extconf.rb"],
    :required_ruby_version => '>= 1.9.2'
  }

  if java?
    # TODO: clean this section up.
    require "rake/javaextensiontask"
    Rake::JavaExtensionTask.new("psych", spec) do |ext|
      jruby_home = RbConfig::CONFIG['prefix']
      ext.ext_dir = 'ext/java'
      ext.lib_dir = 'lib/psych'
      jars = ["#{jruby_home}/lib/jruby.jar"] + FileList['lib/*.jar']
      ext.classpath = jars.map { |x| File.expand_path x }.join ':'
    end
  else
    Rake::ExtensionTask.new "psych", spec do |ext|
      ext.lib_dir = File.join(*['lib', ENV['FAT_DIR']].compact)
    end
  end
end

Hoe.add_include_dirs('.:lib/psych')

task :test => :compile

task :hack_spec do
  $hoe.spec.extra_rdoc_files.clear
end
task 'core:spec' => [:hack_spec, 'gem:spec']

desc "merge psych in to ruby trunk"
namespace :merge do
  basedir = File.expand_path File.dirname __FILE__
  rubydir = File.join ENV['HOME'], 'git', 'ruby'
  mergedirs = {
    # From                          # To
    [basedir, 'ext', 'psych/']  => [rubydir, 'ext', 'psych/'],
    [basedir, 'lib/']           => [rubydir, 'ext', 'psych', 'lib/'],
    [basedir, 'test', 'psych/'] => [rubydir, 'test', 'psych/'],
  }

  rsync = 'rsync -av --exclude extconf.rb --exclude lib --exclude ".*" --exclude "*.o" --exclude Makefile --exclude mkmf.log --delete'

  task :to_ruby do
    mergedirs.each do |from, to|
      sh "#{rsync} #{File.join(*from)} #{File.join(*to)}"
    end
  end

  task :from_ruby do
    mergedirs.each do |from, to|
      sh "#{rsync} #{File.join(*to)} #{File.join(*from)}"
    end
  end
end

# vim: syntax=ruby
